package dynoptic.model.fifosys.cfsm.fsm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;

import dynoptic.DynopticTest;
import dynoptic.model.alphabet.EventType;
import dynoptic.model.fifosys.channel.ChannelId;

public class FSMTests extends DynopticTest {

    // Non-accepting state at pid 1.
    FSMState init_1;
    // Accepting state at pid 1.
    FSMState accepting_1;

    // Random state at pid 2.
    FSMState state_2;

    Set<FSMState> states;

    // cid: 1->2
    ChannelId cid;
    // cid!m
    EventType e_pid1;
    // e_2
    EventType e2_pid1;

    // e_3
    @Override
    public void setUp() {
        init_1 = new FSMState(false, 1);
        accepting_1 = new FSMState(true, 1);
        state_2 = new FSMState(false, 2);
        cid = new ChannelId(1, 2);
        e_pid1 = EventType.SendEvent("m", cid);
        e2_pid1 = EventType.LocalEvent("e", 1);
        states = new LinkedHashSet<FSMState>();
    }

    @Test
    public void createFSM() {
        states.add(init_1);
        states.add(accepting_1);
        FSM f = new FSM(1, init_1, accepting_1, states);
        assertEquals(f.getAlphabet().size(), 0);
        assertEquals(f.getPid(), 1);
        assertEquals(f.getInitState(), init_1);
        assertEquals(f.getAcceptState(), accepting_1);
    }

    @Test
    public void createFSMWithTxns() {
        init_1.addTransition(e_pid1, accepting_1);
        accepting_1.addTransition(e2_pid1, init_1);
        states.add(init_1);
        states.add(accepting_1);

        FSM f = new FSM(1, init_1, accepting_1, states);
        assertEquals(f.getAlphabet().size(), 2);
        assertTrue(f.getAlphabet().contains(e_pid1));
        assertTrue(f.getAlphabet().contains(e2_pid1));

        assertEquals(f.getPid(), 1);
        assertEquals(f.getInitState(), init_1);
        assertEquals(f.getAcceptState(), accepting_1);

    }

    @Test(expected = AssertionError.class)
    public void createBadFSM1() {
        states.add(init_1);
        // error: accepting_1 \not\in states
        FSM f = new FSM(1, init_1, accepting_1, states);
    }

    @Test(expected = AssertionError.class)
    public void createBadFSM2() {
        states.add(init_1);
        states.add(accepting_1);
        states.add(state_2);
        // error: state_2.pid != 2
        FSM f = new FSM(1, init_1, accepting_1, states);
    }

}