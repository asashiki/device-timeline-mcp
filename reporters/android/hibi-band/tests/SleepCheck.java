// SPDX-License-Identifier: AGPL-3.0-only
// Run on a local JVM against the Android Gradle build's two module class directories and Kotlin stdlib.
// Fixtures exercise ingestion semantics only; they are never sent to an app, VPS or Notion.
import com.devicetimeline.agent.band.BandSleep;
import com.example.healthapp.data.protocol.fitness.FitnessDataId;
import com.example.healthapp.data.protocol.fitness.FitnessFileFrame;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

class SleepCheck {
    static final int START = 1793546880, END = START + 7*3600+31*60+11;
    static FitnessFileFrame frame(int type, int version, int flags, boolean complete, int start, int end, int asleep) {
        ByteBuffer body = ByteBuffer.allocate(version == 4 ? 10 : 27).order(ByteOrder.LITTLE_ENDIAN);
        body.put((byte)(complete ? 1 : 0)).putInt(start).putInt(end).put((byte)0);
        byte[] bitmap;
        if (version == 4) bitmap=new byte[]{(byte)flags};
        else {body.put((byte)0).putInt(asleep).putInt(0).putInt(0).putInt(0); bitmap=new byte[]{(byte)(flags>>8),(byte)flags};}
        return new FitnessFileFrame(new FitnessDataId(start,32,version,type),bitmap,body.array());
    }
    static void check(boolean result) { if (!result) throw new AssertionError(); }
    public static void main(String[] ignored) {
        var nativeReport=BandSleep.Companion.decode(frame(33,4,0xc0,true,START,END,0),END+600L);
        check(nativeReport!=null && nativeReport.getStart()==START && nativeReport.getEnd()==END &&
              nativeReport.getElapsedMinutes()==451 && nativeReport.getAsleepMinutes()==null);
        check(BandSleep.Companion.decode(frame(33,4,0x80,true,START,END,0),END+600L)==null);
        check(BandSleep.Companion.decode(frame(33,4,0xc0,false,START,END,0),END+600L)==null);
        check(BandSleep.Companion.decode(frame(33,4,0xc0,true,START,END,0),END-1L)==null);
        check(BandSleep.Companion.decode(frame(13,4,0xc0,true,START,END,0),END+1L)==null);
        var partial=BandSleep.Companion.provisional(frame(33,4,0xc0,false,START,END,0),END+600L);
        check(partial!=null && partial.getStart()==START && partial.getEnd()==END && partial.getAsleepMinutes()==null);
        check(BandSleep.Companion.provisional(frame(33,4,0x80,false,START,END,0),END+600L)==null);
        check(BandSleep.Companion.provisional(frame(33,4,0xc0,false,START,END,0),END-1L)==null);
        var compact = frame(33,4,0xd0,true,START,END,0);
        var compactNight = BandSleep.Companion.decode(compact,END+60L);
        check(compactNight != null && compactNight.getElapsedMinutes()==451 && compactNight.getAsleepMinutes()==null);
        check(BandSleep.Companion.decode(frame(33,4,0xd0,false,START,END,0),END+60L)==null);
        check(BandSleep.Companion.decode(new FitnessFileFrame(compact.getDataId(),compact.getBitmap(),
            java.util.Arrays.copyOf(compact.getBody(),11)),END+60L)==null);
        check(BandSleep.Companion.decode(new FitnessFileFrame(new FitnessDataId(START+300,32,4,33),
            compact.getBitmap(),compact.getBody()),END+60L)==null);
        int nextWake = END + 86400;
        var later = frame(33,4,0xd0,true,START,nextWake,0);
        check(BandSleep.Companion.decode(later,nextWake+5L)==null);
        check(Long.valueOf(nextWake).equals(BandSleep.Companion.laterWake(later,nextWake+5L,compactNight)));
        check(BandSleep.Companion.laterWake(later,nextWake-1L,compactNight)==null);
        check(BandSleep.Companion.laterWake(compact,END+60L,compactNight)==null);
        check(BandSleep.Companion.laterWake(frame(33,4,0xd0,true,START+1,nextWake,0),nextWake+5L,compactNight)==null);
        check(BandSleep.Companion.laterWake(frame(33,4,0xd0,true,START,START+15*86400,0),START+16*86400L,compactNight)==null);
        check(BandSleep.Companion.laterWake(frame(33,4,0xd0,false,START,nextWake,0),nextWake+5L,compactNight)==null);
        var newer=BandSleep.Companion.decode(frame(33,5,0xc800,true,START,END,427),END+600L);
        check(newer!=null && Integer.valueOf(427).equals(newer.getAsleepMinutes()));
        var invalid=BandSleep.Companion.decode(frame(33,5,0xc000,true,START,END,427),END+600L);
        check(invalid!=null && invalid.getAsleepMinutes()==null);
        System.out.println("PASS: v4 timing, valid v5 sleep, no invented sleep, incomplete/future/other types rejected");
    }
}
