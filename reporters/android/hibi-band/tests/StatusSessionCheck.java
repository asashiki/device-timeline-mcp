// Checks real-time status replies and change notifications without starting a Bluetooth session.
import com.example.healthapp.data.protocol.proto.SleepStatus;
import com.example.healthapp.data.protocol.proto.WearPacket;
import java.util.HexFormat;
import static com.example.healthapp.data.protocol.proto.SleepStatus.Observed.*;

class StatusSessionCheck {
    static WearPacket packet(int id, String hex) {
        return new WearPacket(2, id, null, HexFormat.of().parseHex(hex), null, 0);
    }
    static void expect(Object expected, Object got) {
        if (!java.util.Objects.equals(expected, got)) throw new AssertionError(expected + " != " + got);
    }
    public static void main(String[] args) {
        var s = new SleepStatus.Session();
        var wornAwake = packet(78, "82030418012000");
        var wornAsleep = packet(78, "82030418012001");
        var eventWorn = packet(79, "8a03021001");
        var eventOff = packet(79, "8a03021002");
        var sleep = packet(79, "8a03021801");
        var wake = packet(79, "8a03021802");
        expect(null, s.observe(wornAwake));                     // before sending query
        expect(null, s.observe(sleep));                          // no wearing evidence yet
        s.requestStarted();
        expect(AWAKE, s.observe(wornAwake));
        expect(ASLEEP, s.observe(sleep));                        // prior initial wear in same connection
        expect(AWAKE, s.observe(wake));
        expect(NOT_WORN, s.observe(eventOff));
        expect(NOT_WORN, s.observe(sleep));                     // no state after removal
        expect(null, s.observe(eventWorn));                      // re-wearing alone supplies no state
        expect(ASLEEP, s.observe(sleep));
        expect(null, s.observe(packet(79, "8a03021803")));      // unsupported enum; clears evidence
        expect(null, s.observe(wake));
        expect(ASLEEP, s.observe(packet(79, "8a030410011801"))); // both fields in a live notification
        expect(null, s.observe(packet(80, "8a030410011802")));   // diagnostic report
        expect(null, s.observe(new WearPacket(2,79,null,new byte[0],null,7)));
        expect(null, new SleepStatus.Session().observe(sleep)); // new connection has no wear history
        var late = new SleepStatus.Session();
        late.requestStarted();
        expect(null, late.observe(packet(79,"8a0300")));        // unrelated notifications aren't replies
        expect(ASLEEP, late.observe(wornAsleep));               // no arbitrary 1.6s expiry
        expect(1, late.getReplies());
        expect(1, late.getReports());
        System.out.println("Live sleep status session checks passed");
    }
}
