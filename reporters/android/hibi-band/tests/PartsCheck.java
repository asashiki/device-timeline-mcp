// SPDX-License-Identifier: AGPL-3.0-only
// Offline regression for resumed, out-of-order and conflicting partial files.
import com.devicetimeline.agent.band.BandParts;
import com.example.healthapp.data.protocol.fitness.FitnessDataId;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

class PartsCheck {
    static byte[] pack(int total, int part, byte[] bytes) {
        byte[] out = new byte[bytes.length+4]; out[0]=(byte)total; out[2]=(byte)part;
        System.arraycopy(bytes,0,out,4,bytes.length); return out;
    }
    static void check(boolean condition) { if(!condition) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        var dir = Files.createTempDirectory("hibi-parts-test").toFile();
        var id = new FitnessDataId(1793546880,32,4,33);
        var foreign = new FitnessDataId(1793546870,32,4,33);
        var content = Arrays.copyOf(id.toByteArray(),32);
        for(int i=7;i<28;i++) content[i]=(byte)(2*i);
        var crc = new CRC32(); crc.update(content,0,28);
        for(int i=0;i<4;i++) content[28+i]=(byte)(crc.getValue() >>> (8*i));
        byte[] first=pack(3,1,Arrays.copyOfRange(content,0,12)), second=pack(3,2,Arrays.copyOfRange(content,12,24)), last=pack(3,3,Arrays.copyOfRange(content,24,32));
        try {
            var run = new BandParts(dir,"peer");
            check(run.accept(first)==null && run.accept(last)==null && run.getMaxUnique()==2);
            var resumed=new BandParts(dir,"peer");
            check(resumed.accept(second)==null && resumed.getOrphan()==1);
            check(resumed.accept(pack(3,1,foreign.toByteArray()))==null && resumed.getFirstSeen()==1);
            check(resumed.accept(first)==null);
            var wrong=second.clone(); wrong[5]^=1;
            check(resumed.accept(wrong)==null && resumed.getBadCrc()==1);
            check(resumed.accept(second)==null); // old last fragment dropped following a CRC failure
            check(Arrays.equals(resumed.accept(last),pack(1,1,content)));
            var other=new BandParts(dir,"other");
            check(other.accept(first)==null && other.accept(last)==null);
            resumed.complete();
            // Unsolicited complete minute file arrives during a 3-part sleep transfer:
            // send the actual minute header, never relabel as the requested sleep ID.
            var mixed = new BandParts(dir,"mixed");
            var minute = new FitnessDataId(1793546970,32,4,0);
            var single = content.clone(); System.arraycopy(minute.toByteArray(),0,single,0,7);
            crc.reset(); crc.update(single,0,single.length-4);
            for(int n=0;n<4;n++) single[single.length-4+n]=(byte)(crc.getValue() >>> (8*n));
            check(mixed.accept(first)==null);
            check(Arrays.equals(mixed.accept(pack(1,1,single)),pack(1,1,single)));
            mixed.complete();
            check(mixed.accept(second)==null);
            check(Arrays.equals(mixed.accept(last),pack(1,1,content)));
            mixed.complete();
            check(mixed.getHeadCounts().get(0)==1 && mixed.getHeadCounts().get(33)==1);
            System.out.println("PASS: independent peer/first-ID, late continuation rejected, checksummed cross-session recovery, conflict recovery");
        } finally { try(var s=Files.walk(dir.toPath())) { s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); } }
    }
}
