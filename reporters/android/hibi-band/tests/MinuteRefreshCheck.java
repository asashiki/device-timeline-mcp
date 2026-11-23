import com.devicetimeline.agent.band.RecentMinuteFiles;
import com.example.healthapp.data.protocol.fitness.FitnessDataId;
import java.util.List;
class MinuteRefreshCheck {
  static void check(boolean v){if(!v) throw new AssertionError();}
  public static void main(String[] args){
    long now=1000000;
    var prev=new FitnessDataId(now-300,32,4,0);
    var curr=new FitnessDataId(now,32,4,0);
    var expired=new FitnessDataId(now-10000,32,4,0);
    var sleep=new FitnessDataId(now-300,32,4,33);
    var mem=new RecentMinuteFiles("");
    String saved=mem.add(List.of(curr,prev,sleep,expired),now);
    check(saved!=null);
    var next=new RecentMinuteFiles(saved);
    check(next.forRefresh(now,List.of()).equals(List.of(prev)));
    check(next.forRefresh(now+300,List.of()).equals(List.of(curr,prev)));
    check(next.forRefresh(now+9000,List.of()).isEmpty());
    System.out.println("PASS: previously verified recent files are re-read after growth even without a current index entry.");
  }
}
