package csight.invariants;

import java.util.List;

import csight.invariants.checkers.NFbyChecker;

import synoptic.model.event.DistEventType;

/** A CSight representation of the NFby invariant. */
public class NeverFollowedBy extends BinaryInvariant {

    public NeverFollowedBy(DistEventType typeFirst, DistEventType typeSecond) {
        super(typeFirst, typeSecond, "NFby");
    }

    @Override
    public String scmBadStateQRe() {
        checkInitialized();

        // There is an 'a', preceded by any number of 'a' or 'b', but which is
        // followed by at least one 'b'. This last 'b' can be intermingled in
        // any way with 'a' and other 'b' instances.
        return someSynthEventsQRe() + " . " + firstSynthEventsQRe() + " . "
                + someSynthEventsQRe() + " . " + secondSynthEventsQRe() + " . "
                + someSynthEventsQRe();
    }

    @Override
    public String promelaNeverClaim() {
        // Never claim generated by Spin with command
        // spin -f "!([]((a) -> []!(b)))"
        // Then we substitute a and b for our conditionals.

        // The claim is true if we see a "b" after seeing an "a".
        String ret = "";
        ret += String.format("never { /* !([](%s -> []!%s)) */ \n",
                firstNeverEvent(), secondNeverEvent());
        ret += "State_no_a:\n";
        ret += "    do\n";
        ret += String.format("      :: (!%s) -> goto State_no_a\n",
                firstNeverEvent()); // We haven't seen a.
        ret += String.format("      :: %s -> goto State_seen_a\n",
                firstNeverEvent()); // We saw an a.
        ret += "    od;\n";
        ret += "State_seen_a:\n";
        ret += "    do\n";
        ret += String.format("      :: ((%s)) -> goto accept_all\n",
                secondNeverEvent()); // We saw a b, so we accept the claim.
        ret += String.format("      :: ((!(%s))) -> goto State_seen_a\n",
                secondNeverEvent()); // We haven't seen b.
        ret += "    od;\n";
        ret += "accept_all:\n";
        ret += "skip\n";
        ret += "}\n";
        return ret;

    }

    @Override
    public boolean satisfies(List<DistEventType> eventsPath) {
        // Whether or not we've seen 'first' so far.
        boolean seenFirst = false;
        for (DistEventType e : eventsPath) {
            if (!seenFirst && e.equals(first)) {
                seenFirst = true;
                continue;
            }

            // If we saw 'first', and e is 'second' then first is followed by
            // second in eventsPath => ~(first NFby second).
            if (seenFirst && e.equals(second)) {
                return false;
            }
        }
        // Never saw 'first' followed by 'second' => first NFby second
        return true;
    }

    @Override
    public NFbyChecker newChecker() {
        return new NFbyChecker(this);
    }
}
