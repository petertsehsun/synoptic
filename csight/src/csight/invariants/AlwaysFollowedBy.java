package csight.invariants;

import java.util.List;

import csight.invariants.checkers.AFbyChecker;

import synoptic.model.event.DistEventType;

/** A CSight representation of the AFby invariant. */
public class AlwaysFollowedBy extends BinaryInvariant {

    public AlwaysFollowedBy(DistEventType typeFirst, DistEventType typeSecond) {
        super(typeFirst, typeSecond, "AFby");

        // It is impossible to mine x AFby x in a linear trace.
        assert !typeFirst.equals(typeSecond);

    }

    @Override
    public String scmBadStateQRe() {
        checkInitialized();

        // There is an 'a' that is preceded by 'a' or 'b' but is not followed
        // any later by any 'b', though it might be followed by more a's.
        return someSynthEventsQRe() + " . " + firstSynthEventsQRe() + "^+";
    }

    @Override
    public String promelaNeverClaim() {
        // The invariant does not hold if the never claim is accepted.

        // The never claim is accepted there is an "a" that
        // is not followed by a "b".
        String ret = "";
        ret += String.format("never  {    /* !([]((%s) -> <>(%s))) */\n",
                first.toPromelaString(), second.toPromelaString());

        ret += "wait_a:\n"; // "a"s so far have a "b".
        ret += "    do\n";
        ret += String
                .format("      :: %s -> goto need_b;\n", firstNeverEvent());
        ret += String.format("      :: !%s -> goto wait_a;\n",
                firstNeverEvent());
        ret += "    od;\n";

        // We want event b in this state. If we reach the end without it, we
        // accept the never claim.
        ret += "need_b:\n"; // Saw a, but haven't seen b.
        ret += "    do\n";
        ret += String.format("      :: %s -> goto wait_a;\n",
                secondNeverEvent());
        ret += String.format("      :: !%s -> goto need_b;\n",
                secondNeverEvent());
        // If b's process has ended, we can't get a b.
        ret += String
                .format("      :: ( !%s && ENDSTATECHECK && EMPTYCHANNELCHECK) -> break;\n",
                        secondNeverEvent());
        ret += "    od;\n";

        ret += "}\n";

        return ret;

    }

    @Override
    public boolean satisfies(List<DistEventType> eventsPath) {
        // T: 'first' appears after all 'second'
        // F: 'first' does not appear, or 'second' appears after last 'first'
        boolean lastFirst = false;

        for (DistEventType e : eventsPath) {
            if (e.equals(first)) {
                lastFirst = true;
                continue;
            }

            if (e.equals(second)) {
                lastFirst = false;
            }
        }
        return !lastFirst;
    }

    @Override
    public AFbyChecker newChecker() {
        return new AFbyChecker(this);
    }
}
