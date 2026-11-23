import com.devicetimeline.agent.ForegroundTimeline;
class ForegroundTimelineCheck {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        var timeline = new ForegroundTimeline();
        check(timeline.now(1,true).getPkg()==null);
        var visit=timeline.resume("one","Home",2000);
        check(visit.size()==1 && visit.get(0).getAt()==2000);
        var hourLater=timeline.now(3602000,true);
        check("one".equals(hourLater.getPkg()) && hourLater.getAt()==3602000);
        timeline.resume("one","Video",3602400);
        check(timeline.pause("one","Home",3602500).isEmpty());
        check("one".equals(timeline.now(3602600,true).getPkg()));
        var leave=timeline.pause("one","Video",3602900);
        check(leave.get(0).getAt()==3602900 && timeline.now(3603000,true).getPkg()==null);
        timeline.resume("two","Main",4000000);
        var lock=timeline.inactive(4001000,false);
        check(lock.size()==2 && lock.get(0).getAt()==4001000);
        timeline.inactive(4001001,true);
        timeline.wake(false);
        check(timeline.resume("two","Main",4002000).isEmpty());
        check("android.idle".equals(timeline.now(4002000,false).getPkg()));
        timeline.wake(true);
        check(timeline.now(4003000,true).getPkg()==null);
        timeline.resume("three","Main",4003100);
        check("three".equals(timeline.now(4003200,true).getPkg()));
        check("android.idle".equals(timeline.now(4003300,false).getPkg()));
        check(timeline.now(4003400,true).getPkg()==null);
        System.out.println("PASS: sustained use, exact departure, late activity stop, keyguard and screen transitions, unknown on unlock.");
    }
}
