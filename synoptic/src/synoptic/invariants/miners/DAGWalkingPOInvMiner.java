package synoptic.invariants.miners;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import synoptic.invariants.ITemporalInvariant;
import synoptic.invariants.TemporalInvariantSet;
import synoptic.main.TraceParser;
import synoptic.model.DistEventType;
import synoptic.model.EventNode;
import synoptic.model.EventType;
import synoptic.model.interfaces.IGraph;
import synoptic.model.interfaces.ITransition;
import synoptic.util.InternalSynopticException;

/**
 * Implements a temporal invariant mining algorithm for partially ordered logs,
 * by walking the corresponding DAG trace structure in the forward and reverse
 * directions. This algorithm is a generalization of the ChainWalkingTOInvMiner
 * (therefore it passes the same unit tests). <br/>
 * <br/>
 * The algorithm uses followed-by and precedes counts to mine the invariants.
 * That is, it counts the number of times x is followed-by y, and the number of
 * times x precedes y for all event types x and y in the traces. It then re-uses
 * TemporalInvariantSet.extractInvariantsFromWalkCounts() to turn these counts
 * into valid temporal invariants.
 */
public class DAGWalkingPOInvMiner extends InvariantMiner {

    @Override
    public TemporalInvariantSet computeInvariants(IGraph<EventNode> g) {
        // Determine whether to mine concurrency invariants or not by testing
        // the event type of _some_ node in g -- concurrency invariants are
        // mined for nodes of DistEventType.
        boolean mineConcurrencyInvariants = false;
        if (g.getNodes().iterator().next().getEType() instanceof DistEventType) {
            mineConcurrencyInvariants = true;
        }
        return computeInvariants(g, mineConcurrencyInvariants);
    }

    /**
     * Computes invariants for a graph g. mineConcurrencyInvariants determines
     * whether distributed invariants of the form (a AlwaysConcurrentWith b, a
     * NeverConcurrentWith b) will be mined and also returned.
     * 
     * @param g
     *            input graph to mine invariants over
     * @param mineConcurrencyInvariants
     *            whether or not to mine distributed invariants
     * @return the set of mined invariants
     */
    public TemporalInvariantSet computeInvariants(IGraph<EventNode> g,
            boolean mineConcurrencyInvariants) {
        String relation = TraceParser.defaultRelation;

        if (g.getInitialNodes().isEmpty() || g.getInitialNodes().size() != 1) {
            throw new InternalSynopticException(
                    "Cannot compute invariants over a graph that doesn't have exactly one INITIAL node.");
        }

        EventNode initNode = g.getInitialNodes().iterator().next();
        if (!initNode.getEType().isInitialEventType()) {
            throw new InternalSynopticException(
                    "Cannot compute invariants over a graph that doesn't have exactly one INITIAL node.");
        }

        // TODO: we can set the initial capacity of the following HashMaps more
        // optimally, e.g. (N / 0.75) + 1 where N is the total number of event
        // types. See:
        // http://stackoverflow.com/questions/434989/hashmap-intialization-parameters-load-initialcapacity

        // Tracks global event counts globally -- across all traces.
        Map<EventType, Integer> gEventCnts = new LinkedHashMap<EventType, Integer>();
        // Tracks global followed-by counts -- across all traces.
        Map<EventType, Map<EventType, Integer>> gFollowedByCnts = new LinkedHashMap<EventType, Map<EventType, Integer>>();
        // Tracks global precedence counts -- across all traces.
        Map<EventType, Map<EventType, Integer>> gPrecedesCnts = new LinkedHashMap<EventType, Map<EventType, Integer>>();
        // Tracks which events were observed across all traces.
        Set<EventType> gAlwaysFollowsINITIALSet = null;

        // The set of all event types seen in a single trace.
        Set<EventType> tSeenETypes = new LinkedHashSet<EventType>();
        // Maps a node in the trace DAG to the number of parents this node has
        // in the DAG. This is computed during a pre-traversal of the DAG
        // (modified in the forward trace traversal).
        Map<EventNode, Integer> tNodeToNumParentsMap = new LinkedHashMap<EventNode, Integer>();

        // Maps a node in the trace to the number of children that are below it.
        // This is computed during pre-traversal (modified in reverse trace
        // traversal).
        Map<EventNode, Integer> tNodeToNumChildrenMap = new LinkedHashMap<EventNode, Integer>();

        // Maps a node to a set of nodes that immediately precede this node (the
        // node's parents). Build during pre-traversal, and used for mining
        // FollowedBy counts. We do this because nodes only know about their
        // children, and not their parents.
        Map<EventNode, Set<EventNode>> tNodeParentsMap = new LinkedHashMap<EventNode, Set<EventNode>>();

        // Maintains a map of trace id to the set of initial nodes in the trace.
        Map<Integer, Set<EventNode>> traceIdToInitNodes = buildTraceIdToInitNodesMap(initNode);

        // A couple of hash sets for containing parents of special nodes.
        Set<EventNode> initNodeHashSet = new LinkedHashSet<EventNode>();
        initNodeHashSet.add(initNode);
        Set<EventNode> emptyNodeHashSet = new LinkedHashSet<EventNode>();

        // Given two event types e1, e2. if gEventCoOccurrences[e1] contains e2
        // then there are instances of e1 and e2 that appeared in the same
        // trace.
        Map<EventType, Set<EventType>> gEventCoOccurrences = new LinkedHashMap<EventType, Set<EventType>>();

        // Counts the number of times an event type appears in a trace.
        Map<EventType, Integer> tEventCnts = new LinkedHashMap<EventType, Integer>();

        // For an EventNode n, maintains the set of nodes that followed this
        // node in the trace. In practice, only nodes with multiple children
        // will have a record.
        Map<EventNode, Set<EventNode>> tFollowingNodeSets = new LinkedHashMap<EventNode, Set<EventNode>>();

        // For an EventNode n, maintains the set of nodes that preceded this
        // node in the trace. In practice, only nodes with multiple parents will
        // have a record.
        Map<EventNode, Set<EventNode>> tPrecedingNodeSets = new LinkedHashMap<EventNode, Set<EventNode>>();

        // For "a NeverConcurrentWith b" to hold it must be the case that for
        // an a instance and b instance that co-occur in a trace, the two
        // instances must be totally ordered. Therefore, either a is followed by
        // b or a is preceded by b. Whatever the case, the sum total of b's that
        // the a instance must be preceded AND followed by must be the TOTAL
        // number of b instances in the trace. Notice that if this property
        // holds for all a instances, then it must likewise hold for all b
        // instances (it is symmetric, and the NeverConcurrentWith
        // invariant is symmetric for the same reason).
        //
        // Therefore, to check if "a NeverConcurrentWith b" is true, we can sum
        // the total number of b's that follow or precede each a and check if
        // this total equals number of a's * number of b's. If yes, then the
        // invariant is true. Further, to check if the invariant is true across
        // all traces, we perform the same computation on each trace.
        //
        // The tTypeFollowingTypeCnts and tTypePrecedingTypeCnts structures are
        // used for computing per-trace counts described above. The
        // gEventTypesOrderedBalances structure is the global computation, which
        // maintains the difference between the all the per-trace
        // precedes/follows counts for types (e1,e2) and the products (number of
        // e1's * number of e2's).

        // For two event types e1, e2 in a trace; at the end of the trace
        // traversal tTypeFollowingTypeCnts[e1][e2] will represent the total
        // count of e2 event instances that followed each of the e1 event
        // instances.
        //
        // For example, if the trace is linear: a,a,b,a,b
        // Then tTypeFollowingTypeCnts[a][b] = 5
        // 2 b's follow the first and second a, and 1 b follows the 3rd a, so
        // 2+2+1 =5
        Map<EventType, Map<EventType, Integer>> tTypeFollowingTypeCnts = new LinkedHashMap<EventType, Map<EventType, Integer>>();

        // For two event types e1, e2 in a trace; at the end of the trace
        // traversal tTypePrecedingTypeCnts[e1][e2] will represent the total
        // count of e2 event instances that preceded each of the e1 event
        // instances.
        //
        // Using the prior linear trace example: a,a,b,a,b
        // tTypePrecedingTypeCnts[a][b] = 1 because
        // 0 b's precede the first two a's, and 1 b precedes the third a, so
        // 0+0+1 =1
        //
        // Notice that tTypePrecedingTypeCnts[a][b] +
        // tTypeFollowingTypeCnts[a][b] = 6
        // and number of a's * number of b's = 2*3 = 6
        // since 6 == 6, the invariant "a NeverConcurrentWith b" is true for
        // this example trace.
        Map<EventType, Map<EventType, Integer>> tTypePrecedingTypeCnts = new LinkedHashMap<EventType, Map<EventType, Integer>>();

        // For two event types e1, e2 across all the traces,
        // gEventTypesOrderedBalances[e1][e2] represents the ordering balance.
        // That is, if gEventTypesOrderedBalances[e1][e2] = 0 then every
        // instance of e1 and every instance of e2 that appeared in the same
        // trace were totally ordered. Otherwise,
        // gEventTypesOrderedBalances[e1][e2] is negative, indicating that in
        // some trace some instance of e1 and some instance of e2 were in the
        // same trace but were not ordered.
        Map<EventType, Map<EventType, Integer>> gEventTypesOrderedBalances = new LinkedHashMap<EventType, Map<EventType, Integer>>();

        // Iterate through all the traces.
        for (Set<EventNode> initTraceNodes : traceIdToInitNodes.values()) {
            tNodeParentsMap.put(initNode, emptyNodeHashSet);

            // ///////////////////
            // TODO: this assumes that we have a single terminal node. But a PO
            // trace could have multiple terminals. We need to treat terminals
            // as we do with initial nodes -- maintain a termTraceNodes list.
            // ///////////////////

            EventNode termNode = null, termNodeNew = null;
            for (EventNode curNode : initTraceNodes) {
                tNodeParentsMap.put(curNode, initNodeHashSet);
                // A pre-processing step: builds the parent\child counts maps,
                // the parents map, the tSeenETypes set, and determines the
                // terminal node in the trace.
                termNodeNew = preTraverseTrace(curNode, tNodeToNumParentsMap,
                        tNodeToNumChildrenMap, tNodeParentsMap, tEventCnts,
                        tSeenETypes);
                if (termNodeNew != null) {
                    termNode = termNodeNew;
                }
            }
            assert (termNode != null);

            // For every pair of event types in the trace record that the two
            // types have event instances that co-occur in some trace.
            Set<EventType> toVisitETypes = new LinkedHashSet<EventType>();
            toVisitETypes.addAll(tSeenETypes);
            for (EventType e1 : tSeenETypes) {
                // We don't consider (e1, e1) as these would be useful for local
                // invariants and we don't use conditional counts for mining
                // local invariants; and we do not consider (e1,e2) if we've
                // already considered (e2,e1).
                toVisitETypes.remove(e1);
                for (EventType e2 : toVisitETypes) {
                    // Optimization: We won't be using event co-occurrence
                    // information for
                    // events that are local.
                    if (e1 == e2) {
                        continue;
                    }
                    if (!(e1 instanceof DistEventType)
                            || !(e2 instanceof DistEventType)) {
                        continue;
                    }
                    if (((DistEventType) e1).getPID().equals(
                            ((DistEventType) e2).getPID())) {

                        continue;
                    }
                    // </Optimization>

                    // Record that e1 and e2 co-occur
                    if (!gEventCoOccurrences.containsKey(e1)) {
                        gEventCoOccurrences.put(e1,
                                new LinkedHashSet<EventType>());
                    }
                    gEventCoOccurrences.get(e1).add(e2);
                }
            }

            // logger.info("co-occur-cnts: " +
            // traceCoOccurrenceCnts.toString());

            // ////////////////////////////////////////////////////////////////////////
            // Precedes relations recording: traverse the trace rooted at each
            // initial node in the forward direction.
            for (EventNode curNode : initTraceNodes) {
                forwardTraverseTrace(curNode, tNodeToNumParentsMap, null,
                        tPrecedingNodeSets, tTypePrecedingTypeCnts,
                        gPrecedesCnts, gEventCnts);
            }

            // ////////////////////////////////////////////////////////////////////////
            // FollowedBy relations recording: traverse the trace rooted at
            // termNode in the reverse direction (following the
            // tNodeParentsMap).
            reverseTraverseTrace(termNode, tNodeToNumChildrenMap,
                    tNodeParentsMap, null, tFollowingNodeSets,
                    tTypeFollowingTypeCnts, gFollowedByCnts);

            // Compute the gEventTypesOrderedBalances for the current trace.
            for (EventType e1 : tSeenETypes) {
                for (EventType e2 : tSeenETypes) {
                    // Optimization: we won't be using ordering balance for
                    // events that are local.
                    if (e1 == e2) {
                        continue;
                    }
                    if (!(e1 instanceof DistEventType)
                            || !(e2 instanceof DistEventType)) {
                        continue;
                    }
                    if (((DistEventType) e1).getPID().equals(
                            ((DistEventType) e2).getPID())) {
                        continue;
                    }
                    // </Optimization>

                    int typeFtypeCnt = 0;
                    if (tTypeFollowingTypeCnts.containsKey(e1)
                            && tTypeFollowingTypeCnts.get(e1).containsKey(e2)) {
                        typeFtypeCnt = tTypeFollowingTypeCnts.get(e1).get(e2);
                    }

                    int numE1 = tEventCnts.get(e1);
                    int numE2 = tEventCnts.get(e2);

                    int typePtypeCnt = 0;
                    if (tTypePrecedingTypeCnts.containsKey(e1)
                            && tTypePrecedingTypeCnts.get(e1).containsKey(e2)) {
                        typePtypeCnt = tTypePrecedingTypeCnts.get(e1).get(e2);
                    }

                    if (!gEventTypesOrderedBalances.containsKey(e1)) {
                        gEventTypesOrderedBalances.put(e1,
                                new LinkedHashMap<EventType, Integer>());
                    }

                    int prevBalance = 0;
                    if (gEventTypesOrderedBalances.get(e1).containsKey(e2)) {
                        prevBalance = gEventTypesOrderedBalances.get(e1)
                                .get(e2);
                    }

                    // NOTE: since numE1 * numE2 is always >= typeFtypeCnt +
                    // typePtypeCnt, the value is always <= 0. With 0 indicating
                    // that \forall \hat{e1}, \forall \hat{e2} e1 \precedes e2
                    // or e2 \precedes e1. We use this to deduce that e1 and e2
                    // are never concurrent.
                    gEventTypesOrderedBalances.get(e1).put(
                            e2,
                            prevBalance + typeFtypeCnt + typePtypeCnt
                                    - (numE1 * numE2));
                }
            }

            // Update the AlwaysFollowsINITIALSet set of events by
            // intersecting it with all events seen in this partition.
            if (gAlwaysFollowsINITIALSet == null) {
                // This is the first trace we've processed.
                gAlwaysFollowsINITIALSet = new LinkedHashSet<EventType>(
                        tSeenETypes);
            } else {
                gAlwaysFollowsINITIALSet.retainAll(tSeenETypes);
            }

            // Clear all the per-trace structures to prepare for the next trace.
            tNodeToNumParentsMap.clear();
            tNodeParentsMap.clear();
            tNodeToNumChildrenMap.clear();
            tEventCnts.clear();
            tSeenETypes.clear();
            tFollowingNodeSets.clear();
            tPrecedingNodeSets.clear();
            tTypeFollowingTypeCnts.clear();
            tTypePrecedingTypeCnts.clear();

            // At this point, we've completed all counts computation for the
            // trace rooted at curNode.
        }

        // Extract the AFby, NFby, AP invariants based on counts.
        Set<ITemporalInvariant> pathInvs = extractPathInvariantsFromWalkCounts(
                relation, gEventCnts, gFollowedByCnts, gPrecedesCnts,
                gAlwaysFollowsINITIALSet);

        if (mineConcurrencyInvariants) {
            // Extract the concurrency invariants based on counts.
            Set<ITemporalInvariant> concurInvs = extractConcurrencyInvariantsFromWalkCounts(
                    relation, gEventCnts, gFollowedByCnts, gEventCoOccurrences,
                    gEventTypesOrderedBalances);
            // Merge the two sets.
            mergePathAndConcurrencyInvariants(pathInvs, concurInvs);
        }
        // Return pathInvs, which at this point contains any non-redundant
        // concurrency invariants (if these were also mined -- see above).
        return new TemporalInvariantSet(pathInvs);
    } // /computeInvariants

    /**
     * Recursively, depth-first traverses the trace forward to build the
     * parent\child counts maps, the parents map, the tSeenETypes set, and to
     * determine the terminal node in the trace.
     * 
     * @param curNode
     * @param tNodeToNumParentsMap
     * @param tNodeToNumChildrenMap
     * @param tNodeParentsMap
     * @param tSeenETypes
     * @return the terminal node for this trace
     */
    public EventNode preTraverseTrace(EventNode curNode,
            Map<EventNode, Integer> tNodeToNumParentsMap,
            Map<EventNode, Integer> tNodeToNumChildrenMap,
            Map<EventNode, Set<EventNode>> tNodeParentsMap,
            Map<EventType, Integer> tEventCnts, Set<EventType> tSeenETypes) {

        Set<EventNode> parentNodes;
        EventNode childNode;

        while (true) {
            EventType a = curNode.getEType();

            // Store the total number of children that this node has.
            if (!tNodeToNumChildrenMap.containsKey(curNode)) {
                // If we haven't visited this node yet...
                tNodeToNumChildrenMap.put(curNode, curNode.getTransitions()
                        .size());
                // Also, increment the count of the corresponding event types.
                if (tEventCnts.containsKey(a)) {
                    tEventCnts.put(a, tEventCnts.get(a) + 1);
                } else {
                    tEventCnts.put(a, 1);
                }
            }

            // Increment the number of parents for the current node.
            if (!tNodeToNumParentsMap.containsKey(curNode)) {
                // First time we've seen this node -- it has one parent, and we
                // are going to traverse its sub-tree depth first.
                tNodeToNumParentsMap.put(curNode, 1);
            } else {
                // We've already visited this node -- it has one more parent
                // than before.
                tNodeToNumParentsMap.put(curNode,
                        tNodeToNumParentsMap.get(curNode) + 1);

                // Terminate current traversal, because we've already traversed
                // downward from this point.
                return null;
            }

            // We've hit a TERMINAL node, stop.
            if (curNode.getTransitions().size() == 0) {
                return curNode;
            }

            // Record that we've seen the curNode eType. Note that this
            // set deliberately omits the INITIAL\TERMINAL types.
            tSeenETypes.add(a);

            // curNode has multiple children -- handle them
            // outside of the while loop.
            if (curNode.getTransitions().size() != 1) {
                break;
            }

            // Save the parent-child relationship between this node and the
            // immediately next node (based on the above condition that is just
            // one).
            childNode = curNode.getTransitions().get(0).getTarget();
            if (!tNodeParentsMap.containsKey(childNode)) {
                parentNodes = new LinkedHashSet<EventNode>();
                tNodeParentsMap.put(childNode, parentNodes);
            } else {
                parentNodes = tNodeParentsMap.get(childNode);
            }
            parentNodes.add(curNode);

            // Move on to the next node in the linear sub-trace without
            // recursing.
            curNode = childNode;
        }

        // Handle each of the node's child branches recursively.
        EventNode termNode = null;
        for (ITransition<EventNode> trans : curNode.getTransitions()) {
            childNode = trans.getTarget();

            // Build up the parents map for all the children.
            if (!tNodeParentsMap.containsKey(childNode)) {
                parentNodes = new LinkedHashSet<EventNode>();
                tNodeParentsMap.put(childNode, parentNodes);
            } else {
                parentNodes = tNodeParentsMap.get(childNode);
            }
            parentNodes.add(curNode);

            EventNode ret = preTraverseTrace(childNode, tNodeToNumParentsMap,
                    tNodeToNumChildrenMap, tNodeParentsMap, tEventCnts,
                    tSeenETypes);
            if (ret != null) {
                termNode = ret;
            }
        }
        return termNode;

    } // /preTraverseTrace

    /**
     * Recursively, depth-first traverses the trace in the reverse direction to
     * collect event followed-by count statistics.
     * 
     * @param curNode
     * @param tNodeToNumChildrenMap
     * @param tNodeParentsMap
     * @param tNodeFollowsSetMap
     * @param tFollowingTypeCnts
     * @param gFollowedByCnts
     */
    public void reverseTraverseTrace(EventNode curNode,
            Map<EventNode, Integer> tNodeToNumChildrenMap,
            Map<EventNode, Set<EventNode>> tNodeParentsMap,
            Set<EventNode> tFollowingNodes,
            Map<EventNode, Set<EventNode>> tFollowingNodeSets,
            Map<EventType, Map<EventType, Integer>> tTypeFollowingTypeCnts,
            Map<EventType, Map<EventType, Integer>> gFollowedByCnts) {

        // Merge the nodes following the above branch, including the branching
        // node into the set of nodes preceding curNode.
        Set<EventNode> tFollowingNodeSetsNew = new LinkedHashSet<EventNode>();
        if (tFollowingNodes != null) {
            tFollowingNodeSetsNew.addAll(tFollowingNodes);
        }

        while (true) {
            if (tFollowingNodeSets.containsKey(curNode)) {
                tFollowingNodeSetsNew.addAll(tFollowingNodeSets.get(curNode));
            }

            // This guarantees that we only process curNode once we have
            // traversed all of its children (while accumulating the preceding
            // types in the tFollowsNodeFollowsSetMapingTypes above).
            if (tNodeToNumChildrenMap.get(curNode) > 1) {
                tNodeToNumChildrenMap.put(curNode,
                        tNodeToNumChildrenMap.get(curNode) - 1);

                if (!tFollowingNodeSets.containsKey(curNode)) {
                    tFollowingNodeSets.put(curNode,
                            new LinkedHashSet<EventNode>());
                }
                tFollowingNodeSets.get(curNode).addAll(tFollowingNodeSetsNew);
                return;
            }
            // NOTE: We don't need to decrement tNodeToNumChildrenMap[curNode]
            // because we are guaranteed to never pass through this node again.

            // The current event is 'a', and all following events are 'b' --
            // this notation indicates that an 'a' always occurs prior to a
            // 'b' in the trace.
            EventType a = curNode.getEType();

            // Update the global precedes counts based on the a events that
            // preceded the current b event in this trace.
            Set<EventType> visitedTypes = new LinkedHashSet<EventType>();
            for (EventNode n : tFollowingNodeSetsNew) {
                EventType b = n.getEType();
                if (!visitedTypes.contains(b)) {
                    Map<EventType, Integer> followingLabelCnts;
                    if (!gFollowedByCnts.containsKey(a)) {
                        followingLabelCnts = new LinkedHashMap<EventType, Integer>();
                        gFollowedByCnts.put(a, followingLabelCnts);
                    } else {
                        followingLabelCnts = gFollowedByCnts.get(a);
                    }
                    if (!followingLabelCnts.containsKey(b)) {
                        followingLabelCnts.put(b, 1);
                    } else {
                        followingLabelCnts
                                .put(b, followingLabelCnts.get(b) + 1);
                    }
                }
                visitedTypes.add(b);
            }

            if (!tTypeFollowingTypeCnts.containsKey(a)) {
                tTypeFollowingTypeCnts.put(a,
                        new LinkedHashMap<EventType, Integer>());
            }
            mergeNodesSetIntoMap(tTypeFollowingTypeCnts.get(a),
                    tFollowingNodeSetsNew);

            tFollowingNodeSetsNew.add(curNode);

            // Nodes with multiple parents are handled outside the loop.
            if (tNodeParentsMap.get(curNode).size() != 1) {
                break;
            }

            // Move on to the next node in the trace without recursion.
            curNode = tNodeParentsMap.get(curNode).iterator().next();

            // We've hit the INITIAL node, stop.
            if (tNodeParentsMap.get(curNode).size() == 0) {
                return;
            }
        }

        // Handle each of the node's parent branches recursively.
        Iterator<EventNode> iter = tNodeParentsMap.get(curNode).iterator();

        while (iter.hasNext()) {
            EventNode parentNode = iter.next();
            // We do not create a new copy of following types for each parent,
            // because each parent already has its own -- maintained as part of
            // tNodeFollowsSetMap (built in preTraverseTrace()).

            // Only process those parents that are not INITIAL nodes.
            if (tNodeParentsMap.get(parentNode).size() != 0) {
                reverseTraverseTrace(parentNode, tNodeToNumChildrenMap,
                        tNodeParentsMap, tFollowingNodeSetsNew,
                        tFollowingNodeSets, tTypeFollowingTypeCnts,
                        gFollowedByCnts);
            }
        }
        return;
    } // /reverseTraverseTrace

    /**
     * Recursively, depth-first traverse the trace in the forward direction to
     * collect event precedence count statistics.
     * 
     * @param curNode
     * @param tNodeToNumParentsMap
     * @param tNodePrecedesSetMap
     * @param tPrecedingTypeCnts
     * @param gPrecedesCnts
     * @param gEventCnts
     */
    public void forwardTraverseTrace(EventNode curNode,
            Map<EventNode, Integer> tNodeToNumParentsMap,
            Set<EventNode> tPrecedingNodes,
            Map<EventNode, Set<EventNode>> tPrecedingNodeSets,
            Map<EventType, Map<EventType, Integer>> tTypePrecedingTypeCnts,
            Map<EventType, Map<EventType, Integer>> gPrecedesCnts,
            Map<EventType, Integer> gEventCnts) {

        // Merge the nodes preceding the above branch, including the branching
        // node into the set of nodes preceding curNode.
        Set<EventNode> tPrecedingNodesNew = new LinkedHashSet<EventNode>();
        if (tPrecedingNodes != null) {
            tPrecedingNodesNew.addAll(tPrecedingNodes);
        }

        while (true) {
            if (tPrecedingNodeSets.containsKey(curNode)) {
                tPrecedingNodesNew.addAll(tPrecedingNodeSets.get(curNode));
            }

            // This guarantees that we only process curNode once we have
            // traversed all of its parents (while accumulating the preceding
            // types in tPrecedingNodeSets).
            if (tNodeToNumParentsMap.get(curNode) > 1) {
                tNodeToNumParentsMap.put(curNode,
                        tNodeToNumParentsMap.get(curNode) - 1);

                if (!tPrecedingNodeSets.containsKey(curNode)) {
                    tPrecedingNodeSets.put(curNode,
                            new LinkedHashSet<EventNode>());
                }
                tPrecedingNodeSets.get(curNode).addAll(tPrecedingNodesNew);
                return;
            }
            // NOTE: We don't need to decrement tNodeToNumParentsMap[curNode]
            // because we are guaranteed to never pass through this node again.

            // The current event is 'b', and all prior events are 'a' --
            // this notation indicates that an 'a' always occurs prior to a
            // 'b' in the trace.
            EventType b = curNode.getEType();

            // Update the global precedes counts based on the a event types that
            // preceded the current b event in this trace.
            // i.e., gPrecedesCnts[a][b]++
            Set<EventType> visitedTypes = new LinkedHashSet<EventType>();
            for (EventNode n : tPrecedingNodesNew) {
                EventType a = n.getEType();
                if (!visitedTypes.contains(a)) {
                    Map<EventType, Integer> precedingLabelCnts;
                    if (!gPrecedesCnts.containsKey(a)) {
                        precedingLabelCnts = new LinkedHashMap<EventType, Integer>();
                        gPrecedesCnts.put(a, precedingLabelCnts);
                    } else {
                        precedingLabelCnts = gPrecedesCnts.get(a);
                    }
                    if (!precedingLabelCnts.containsKey(b)) {
                        precedingLabelCnts.put(b, 1);
                    } else {
                        precedingLabelCnts
                                .put(b, precedingLabelCnts.get(b) + 1);
                    }
                }
                visitedTypes.add(a);
            }

            if (!tTypePrecedingTypeCnts.containsKey(b)) {
                tTypePrecedingTypeCnts.put(b,
                        new LinkedHashMap<EventType, Integer>());
            }
            mergeNodesSetIntoMap(tTypePrecedingTypeCnts.get(b),
                    tPrecedingNodesNew);

            tPrecedingNodesNew.add(curNode);

            // Update the global event counts.
            if (!gEventCnts.containsKey(b)) {
                gEventCnts.put(b, 1);
            } else {
                gEventCnts.put(b, gEventCnts.get(b) + 1);
            }

            // Nodes with multiple children are handled outside the loop.
            if (curNode.getTransitions().size() != 1) {
                break;
            }

            // Move on to the next node in the trace without recursion.
            curNode = curNode.getTransitions().get(0).getTarget();

            // We've hit a TERMINAL node, stop.
            if (curNode.getTransitions().size() == 0) {
                return;
            }
        }

        // Handle each of the node's child branches recursively.
        for (ITransition<EventNode> trans : curNode.getTransitions()) {
            EventNode childNode = trans.getTarget();
            // We do not create a new copy of preceding types for each child,
            // because each child already has its own -- maintained as part of
            // tNodePrecedesSetMap (built in preTraverseTrace()).

            // Only process children that are not TERMINAL nodes.
            if (childNode.getTransitions().size() > 0) {
                forwardTraverseTrace(childNode, tNodeToNumParentsMap,
                        // tNodePrecedesSetMap,
                        tPrecedingNodesNew, tPrecedingNodeSets,
                        tTypePrecedingTypeCnts, gPrecedesCnts, gEventCnts);
            }
        }
        return;
    } // /forwardTraverseTrace

    /**
     * Merges the count of event types from the set of nodes in src into dst.
     * For example, if src contains instances {a,a',b} and dst is empty then dst
     * will contain {a: 2, b:1}.
     * 
     * @param dst
     * @param src
     */
    private void mergeNodesSetIntoMap(Map<EventType, Integer> dst,
            Set<EventNode> src) {
        if (src == null) {
            return;
        }
        for (EventNode n : src) {
            EventType e = n.getEType();
            if (!dst.containsKey(e)) {
                dst.put(e, 1);
            } else {
                dst.put(e, dst.get(e) + 1);
            }
        }
    }

    /**
     * A helper function to merge the src map into the dst map, by adding the
     * leaf values, or adding to an implicit 0 in the dst map if it doesn't
     * contain the requistite keys.
     * 
     * @param dst
     * @param src
     */
    // private void mergeMaps(Map<EventType, Integer> dst,
    // Map<EventType, Integer> src) {
    // if (src == null) {
    // return;
    // }
    // for (EventType e : src.keySet()) {
    // if (!dst.containsKey(e)) {
    // dst.put(e, src.get(e));
    // } else {
    // dst.put(e, dst.get(e) + src.get(e));
    // }
    // }
    // }
}
