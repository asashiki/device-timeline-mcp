// SPDX-License-Identifier: AGPL-3.0-only
// Simulate two independent reader processes, with an empty index on the second connection.
import com.devicetimeline.agent.band.SleepCatchup;
import com.example.healthapp.data.protocol.fitness.FitnessDataId;
import java.io.File;
import java.nio.file.*;
import java.util.*;

class CatchupCheck {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] unused) throws Exception {
        File dir=Files.createTempDirectory("hibi-sleep-recovery").toFile();
        final String peer="a".repeat(64), other="b".repeat(64);
        final long[] now={1795379400L};
        var night=new FitnessDataId(now[0]-4*3600,32,4,33);
        var nap=new FitnessDataId(now[0]-3600,32,2,12);
        try {
            var first=new SleepCatchup(dir,peer,()->now[0]);
            first.discover(List.of(night,night));
            check(first.due(Set.of(),3).equals(List.of(night)));
            first.attempted(List.of(night)); // socket closes; no verified frame was received
            check(first.due(Set.of(),3).isEmpty());
            now[0]+=300;
            var reconnect=new SleepCatchup(dir,peer,()->now[0]);
            reconnect.discover(List.of()); // no band index entry this time
            check(reconnect.due(Set.of(),3).equals(List.of(night)));
            check(new SleepCatchup(dir,other,()->now[0]).due(Set.of(),3).isEmpty());
            reconnect.attempted(List.of(night));
            reconnect.persisted(night,SleepCatchup.State.UNFINISHED);
            now[0]+=300;
            reconnect.discover(List.of(nap));
            check(reconnect.due(Set.of(),3).containsAll(List.of(night,nap)));
            // A verified, still-open report with the same ID must stay eligible every
            // polling cycle rather than escalating to hours between comparisons.
            for (int poll=0; poll<6; poll++) {
                reconnect.attempted(List.of(night));
                reconnect.persisted(night,SleepCatchup.State.UNFINISHED);
                now[0]+=300;
                check(reconnect.due(Set.of(),3).contains(night));
            }
            reconnect.attempted(List.of(night,nap));
            reconnect.persisted(night,SleepCatchup.State.COMPLETED);
            reconnect.discover(List.of(night));
            reconnect.persisted(night,SleepCatchup.State.UNFINISHED); // stale incomplete duplicate
            now[0]+=300;
            var later=new SleepCatchup(dir,peer,()->now[0]);
            check(later.due(Set.of(),3).equals(List.of(nap)) && later.outstanding()==1);
            now[0]+=15*86400;
            var expired=new SleepCatchup(dir,peer,()->now[0]);
            check(expired.due(Set.of(),3).isEmpty() && expired.outstanding()==0);
            System.out.println("PASS: disconnected pending ID survives restart and missing index; bounded retry and peer isolation; unfinished file updates and duplicate completion suppression; expiration");
        } finally { try(var s=Files.walk(dir.toPath())) {s.sorted(Comparator.reverseOrder()).forEach(p->p.toFile().delete());} }
    }
}
