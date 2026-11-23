// SPDX-License-Identifier: AGPL-3.0-only
// Authenticated fitness list decoder regression using deterministic offline fixtures.
import com.devicetimeline.agent.band.IndexLists;
import com.example.healthapp.data.protocol.fitness.FitnessDataId;
import com.example.healthapp.data.protocol.proto.WearPacket;
import java.util.Arrays;

class IndexListsCheck {
    static byte[] combine(byte[]... parts) {
        byte[] out = new byte[Arrays.stream(parts).mapToInt(b -> b.length).sum()];
        int offset = 0;
        for (byte[] part : parts) { System.arraycopy(part,0,out,offset,part.length); offset += part.length; }
        return out;
    }
    static byte[] ids(int field, byte[] bytes) { return combine(new byte[]{(byte)(8*field+2),(byte)bytes.length},bytes); }
    static void check(boolean yes) { if (!yes) throw new AssertionError(); }
    public static void main(String[] args) {
        var a=new FitnessDataId(1793546880L,32,4,0);
        var b=new FitnessDataId(1793546940L,32,4,33);
        var c=new FitnessDataId(1793547000L,32,2,12);
        var d=new FitnessDataId(1793547060L,32,2,9);
        var index=new IndexLists();
        // One envelope can contain several fitness blocks. Each list block may
        // contain several concatenated IDs, and subsequent replies may add more.
        var envelope=combine(new byte[]{8,8,16,2},ids(10,ids(2,combine(a.toByteArray(),b.toByteArray()))),ids(10,ids(3,c.toByteArray())));
        check(index.observe(WearPacket.Companion.decode(envelope)));
        var another=new WearPacket(8,2,null,null,ids(2,combine(c.toByteArray(),d.toByteArray())),null);
        check(index.observe(another));
        check(index.list(2).equals(java.util.List.of(a,b,c,d)));
        check(index.messageCount(2)==2 && index.list(1)==null);
        check(!index.observe(new WearPacket(8,2,null,null,ids(2,new byte[3]),null)));
        check(index.list(2).size()==4);
        check(index.observe(new WearPacket(8,1,null,null,ids(2,new byte[0]),null)) && index.list(1).isEmpty());
        System.out.println("PASS: packet/list field merging, concatenated IDs, late reply, malformed lengths and missing vs empty lists");
    }
}
