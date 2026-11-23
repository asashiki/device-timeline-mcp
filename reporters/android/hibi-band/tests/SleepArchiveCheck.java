// SPDX-License-Identifier: AGPL-3.0-only
// Local synthetic wire-format checks. No entry points to upload fixtures.
import com.devicetimeline.agent.band.*;
import com.example.healthapp.data.protocol.fitness.*;
import java.nio.*;
import java.util.List;

class SleepArchiveCheck {
  static void check(boolean yes) { if (!yes) throw new AssertionError(); }
  static void putTypeAndId(byte[] whole, FitnessDataId id) {
    whole[0] = 1; whole[2] = 1; System.arraycopy(id.toByteArray(),0,whole,4,7);
  }
  public static void main(String[] ignored) {
    final int day = 1793546880, end = day + 199*60+10;
    var id = new FitnessDataId(day,32,2,12);
    byte[] raw = new byte[12+29+4]; putTypeAndId(raw,id);
    ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    b.put(12,(byte)0xff); b.put(13,(byte)0xff);
    b.putShort(12+7,(short)185); b.putInt(12+9,day); b.putInt(12+13,end);
    var frame = SleepArchive.INSTANCE.envelope(raw);
    var session = BandSleep.Companion.decode(frame,end+60);
    check(session != null && session.getAsleepMinutes()==185 && session.getElapsedMinutes()==199);
    check(BandSleep.Companion.decode(frame,end-1)==null);
    var unknown = new FitnessDataId(day,32,2,13);
    putTypeAndId(raw,unknown);
    check(SleepArchive.INSTANCE.envelope(raw)!=null && BandSleep.Companion.decode(SleepArchive.INSTANCE.envelope(raw),end+60)==null);
    var label = SleepArchive.INSTANCE.label(List.of(id,unknown));
    check(label.contains("型12×1") && label.contains("型13×1"));
    check(SleepArchive.INSTANCE.recent(id,end) && SleepArchive.INSTANCE.recent(id,end+3*86400) && !SleepArchive.INSTANCE.recent(id,end+8*86400));
    var alt = new FitnessDataId(day,32,2,32);
    raw = new byte[12+1+9+4]; putTypeAndId(raw,alt);
    b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN); b.put(12,(byte)0xc0);b.put(13,(byte)1);
    b.putInt(14,day);b.putInt(18,end);session=BandSleep.Companion.decode(SleepArchive.INSTANCE.envelope(raw),end+60);
    check(session!=null && session.getAsleepMinutes()==null && session.getStart()==day && session.getEnd()==end);
    raw[13]=0; check(BandSleep.Companion.decode(SleepArchive.INSTANCE.envelope(raw),end+60)==null);
    System.out.println("PASS: index categories and dates; 12v2 explicit session, 32v2 conservative interval; unknown summaries preserved");
  }
}
