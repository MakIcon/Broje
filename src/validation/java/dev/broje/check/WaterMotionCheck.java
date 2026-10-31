package dev.broje.check;

import dev.broje.BrojeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import java.nio.file.Files;
import java.io.IOException;
import java.util.ArrayList;

/** Ordered frame sequences with frozen lighting for SSR stability. */
public final class WaterMotionCheck {
    private static String output;
    private static int phase,index;
    private static long next,previous;
    private static final ArrayList<Double> times=new ArrayList<>();
    public static void prepare(Minecraft mc) {
        output=System.getProperty("broje.waterMotionOutput","motion");
        if(!output.matches("[a-zA-Z0-9_-]+"))throw new IllegalArgumentException("Invalid motion output tag");
        try {Files.createDirectories(mc.gameDirectory.toPath().resolve("screenshots").resolve(output));}
        catch(IOException error){throw new IllegalStateException(error);}
    }
    public static float yaw(){return phase==3?30+(index-16)*0.025f:30;}
    public static void frame(Minecraft mc) {
        long now=System.nanoTime();
        if(next==0){next=System.currentTimeMillis()+1500;previous=now;}
        if(phase<2&&previous!=0&&System.currentTimeMillis()>next-800)times.add((now-previous)/1000000.0);
        previous=now;
        if(System.currentTimeMillis()<next)return;
        if(phase<2) {
            times.sort(Double::compare);
            BrojeClient.LOGGER.info("MOTION BENCH {}: median {} ms, p95 {} ms, {} frames",phase==0?"SSR-on":"SSR-off",
                    times.get(times.size()/2),times.get(times.size()*95/100),times.size());
            times.clear();phase++;BrojeClient.CONFIG.setWaterSsr(phase!=1);next=System.currentTimeMillis()+1500;return;
        }
        if(phase==5){BrojeClient.LOGGER.info("MOTION CHECK COMPLETE {}",output);mc.stop();return;}
        String group=phase==2?"still":phase==3?"camera":"waves";
        String name=output+"/"+group+"-"+String.format(java.util.Locale.ROOT,"%02d",index)+".png";
        Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),1,message->{});
        index++;
        if(index>=(phase==2?12:32)){phase++;index=0;}
        if(phase==4)System.setProperty("broje.waterTimeMillis",Integer.toString(2700+index*30));
        next=System.currentTimeMillis()+80;
    }
}
