// SPDX-License-Identifier: AGPL-3.0-only
import com.devicetimeline.agent.band.SerialFileQueue;
import com.example.healthapp.data.protocol.fitness.FitnessDataId;
import java.util.*;

class SerialFileQueueCheck {
    public static void main(String[] args) {
        long now = 1795759200L;
        var sleep = new FitnessDataId(now - 25 * 3600, 32, 4, 33);
        var second = new FitnessDataId(now - 40 * 3600, 32, 4, 33);
        var old = new FitnessDataId(now - 72 * 3600, 32, 4, 33);
        var minute = new FitnessDataId(now - 900, 32, 4, 0);
        var queue = SerialFileQueue.INSTANCE;
        var issued = new LinkedHashSet<FitnessDataId>();
        // The same advertised ID is a candidate even if the journal considers it completed.
        var first = queue.next(now, List.of(sleep), null, List.of(), List.of(minute), issued, Map.of());
        if (!sleep.equals(first)) throw new AssertionError("listed sleep revision was suppressed");
        issued.add(first);
        if (!minute.equals(queue.next(now,List.of(sleep,second),null,List.of(second),List.of(minute),issued,Map.of())))
            throw new AssertionError("late index must not starve minutes after sleep timeout");
        issued.add(minute);
        if (!second.equals(queue.next(now,List.of(sleep,second),null,List.of(),List.of(minute),issued,Map.of())))
            throw new AssertionError("late listed file missing");
        if (queue.next(now,List.of(sleep,old),null,List.of(),List.of(),Set.of(),Map.of(sleep,now-300)) != null)
            throw new AssertionError("avoid re-reading unchanged/old candidates every cycle");
        if (!second.equals(queue.next(now,List.of(),null,List.of(second),List.of(),Set.of(),Map.of())))
            throw new AssertionError("recorded pending request missing without current index");
        System.out.println("PASS: indexed revisions, serial priority, late indices, retry cooldown and recovery");
    }
}
