// SPDX-License-Identifier: AGPL-3.0-only
// Synthetic sensor frames used only for offline regression. No production uploads.
import com.devicetimeline.agent.band.*;
import com.example.healthapp.data.protocol.fitness.*;
import java.nio.*;

class ProgressCheck {
    static void check(boolean v) {if (!v) throw new AssertionError();}
    static FitnessFileFrame v4(int start, boolean closed, int[] hr) {
        ByteBuffer b = ByteBuffer.allocate(18+hr.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte)(closed?1:0)).putInt(start).putInt(0).put((byte)0)
         .putShort((short)60).putShort((short)hr.length).putInt(start);
        for(int v : hr) b.put((byte)v);
        return new FitnessFileFrame(new FitnessDataId(start,32,4,33),new byte[]{(byte)0x90},b.array());
    }
    public static void main(String[] ignored) {
        int start=1793546800, now=start+600;
        var prior=new SleepPoll("previous",now-180,7,null);
        var growth=new BandProgress(start,null,9).compare(prior,now);
        check(growth!=null && growth.getKind().equals("sleep_progress") && growth.getBasis().equals("phone_observed_growth"));
        check(growth.getPreviousAt()==now-180L && growth.getPreviousMinutes()==7);
        check(new BandProgress(start,null,9).compare(null,now)==null);
        check(new BandProgress(start,null,7).compare(prior,now)==null);
        check(new BandProgress(start,null,15).compare(prior,now)==null);
        check(new BandProgress(start,null,9).compare(prior,now+999)==null);
        var ongoing=BandProgress.Companion.from(v4(start,false,new int[]{58,60,59,0,0}),start+127);
        check(ongoing!=null && ongoing.getMinutes()==null && ongoing.getNativeAt()==start+120L);
        var sample=ongoing.compare(new SleepPoll("previous",start+60,null,(long)start+60),start+127L);
        check(sample!=null && sample.getKind().equals("sleep_interval_progress") && sample.getAt()==start+120L);
        check(ongoing.compare(new SleepPoll("previous",start+60,null,(long)start+120),start+127L)==null);
        check(BandProgress.Companion.from(v4(start,true,new int[]{58,60,59}),now)==null);
        check(BandProgress.Companion.from(v4(start,false,new int[]{0,0,0}),now)==null);
        var frame = v4(start,false,new int[]{58,60,59});
        var id = frame.getDataId();
        var ids = java.util.List.of(id);
        var early = new CurrentSleepPoll();
        early.requesting(ids);
        check(early.received(frame, true, now).isEmpty()); // early recovery before TODAY
        var delayed = early.indexed(ids);
        check(delayed.size() == 1 && delayed.get(0).getAt() == now);
        check(early.received(frame,true,now+1).isEmpty()); // repeated frame in same poll
        var after = new CurrentSleepPoll();
        after.indexed(ids);
        check(after.received(frame,true,now).isEmpty()); // unsolicited replay
        after.requesting(ids);
        check(after.received(frame,false,now).isEmpty()); // previously cached fragments
        check(after.received(frame,true,now).size() == 1);
        var historic = new CurrentSleepPoll();
        historic.requesting(ids);
        check(historic.received(frame,true,now).isEmpty());
        check(historic.indexed(java.util.List.of(new FitnessDataId(start-86400,32,4,33))).isEmpty());
        System.out.println("PASS: requires two valid nearby observations; new or stale uploads cannot manufacture sleep");
    }
}
