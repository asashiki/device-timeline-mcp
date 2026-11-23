import com.devicetimeline.agent.ActivityNotice;
import com.devicetimeline.agent.DeliveryNotice;
class ActivityNoticeCheck {
    static void check(boolean condition){if(!condition) throw new AssertionError();}
    public static void main(String[] args){
        var delivery=new DeliveryNotice();
        long wifi=delivery.current();
        delivery.network(false);
        check(!delivery.result(wifi,"已同步") && delivery.message().equals("等待联网"));
        delivery.network(true);
        long mobile=delivery.current();
        check(!delivery.result(wifi,"待联网同步") && delivery.message().equals("正在连接"));
        check(delivery.result(mobile,"已同步"));
        var notice=ActivityNotice.INSTANCE;
        check(notice.format(false,true,"旧应用",delivery.message()).startsWith("屏幕已关闭"));
        check(notice.format(true,true,"旧应用",delivery.message()).startsWith("屏幕已点亮，等待解锁"));
        check(notice.format(true,false,null,delivery.message()).equals("设备记录已开启　·　已同步"));
        check(notice.format(true,false,"当前应用","正在补传").equals("正在记录 · 当前应用　·　正在补传"));
        System.out.println("PASS: screen off, lit keyguard and unlocked; stale network results cannot override reconnection.");
    }
}
