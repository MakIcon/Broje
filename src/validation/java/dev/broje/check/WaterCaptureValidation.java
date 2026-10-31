package dev.broje.check;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Measures native screenshots, without depending on the shader's equations. */
public final class WaterCaptureValidation {
    public static void main(String[] args) throws Exception {
        Path dir=Path.of(args[0]);
        BufferedImage mask=image(dir,"05-material-mask.png");
        int water=0,glass=0,hits=0;
        BufferedImage reflection=image(dir,"04-ssr-hits.png");
        for(int y=100;y<650;y++)for(int x=0;x<1100;x++) {
            int rgb=mask.getRGB(x,y);
            if((rgb>>16&255)>245&&(rgb>>8&255)<8&&(rgb&255)>245) {
                water++;
                if((reflection.getRGB(x,y)&255)>100)hits++;
                if(x<80&&y<280)glass++;
            }
        }
        check(water>100000,"Water atlas mask must cover the basin: "+water);
        check(glass==0,"Green glass must not become water: "+glass);
        check(hits>5000,"SSR must actually hit reflected shore geometry: "+hits);
        BufferedImage resolved=image(dir,"02-water.png");int sharp=0,total=0;
        for(int y=390;y<429;y++)for(int x=750;x<830;x++) {
            int a=resolved.getRGB(x,y),b=resolved.getRGB(x,y+1);int difference=0;
            for(int shift:new int[]{0,8,16})difference=Math.max(difference,Math.abs((a>>shift&255)-(b>>shift&255)));
            if(difference>16)sharp++;total++;
        }
        check(sharp/(double)total<0.05,"Thin roof reflection must not have striped/checker SSR dropouts: "+sharp/(double)total);
        System.out.printf("PASS: grazing SSR sharp pixel fraction %.4f%n",sharp/(double)total);
        compare(dir,"01-vanilla.png","02-water.png",3,"Visible surface replacement");
        compare(dir,"02-water.png","03-sky-only.png",0.5,"SSR changes shore reflections");
        compare(dir,"06-calm.png","07-waves.png",0.4,"World waves change reflections");
        compare(dir,"07-waves.png","08-no-refraction.png",0.05,"Depth-checked refraction changes the bottom");
        compare(dir,"09-underwater.png","10-no-shafts.png",0.4,"Underwater light shafts");
        compare(dir,"10-no-shafts.png","11-no-caustics.png",0.1,"Underwater caustics");
        compare(dir,"09-underwater.png","13-underwater-vanilla.png",3,"Underwater absorption and scattering");
        compare(dir,"09-underwater.png","14-underwater-night.png",3,"Sunlight fades at night");
        compare(dir,"07-waves.png","15-water-with-fog.png",0.3,"Air fog still composites with water");
        verifyUnderside(dir);
        verifyWaterline(dir);
        verifyBeamsAndSeam(dir);
        verifyContactDepthAndSun(dir);
        verifyShoreAndBloom(dir);
        for(int n=1;n<=16;n++) {
            // Explicit list above also verifies all feature images; these remaining
            // views must contain real scene detail, not an all-black GPU output.
            if(n==12||n==16) {
                BufferedImage frame=image(dir,n==12?"12-surface-below.png":"16-master-off.png");
                double sum=0,min=255,max=0;
                for(int y=100;y<650;y+=4)for(int x=100;x<1000;x+=4) {
                    int rgb=frame.getRGB(x,y);double l=((rgb>>16&255)+(rgb>>8&255)+(rgb&255))/3.0;
                    sum+=l;min=Math.min(min,l);max=Math.max(max,l);
                }
                check(max-min>30&&sum>10000,"Surface below / master-off must preserve scene detail");
            }
        }
        Path log=dir.getParent().resolve("logs/latest.log");
        if(Files.exists(log)) {
            String text=Files.readString(log);
            check(text.contains("WATER CHECK COMPLETE")&&!text.contains("Water initialization failed")
                    &&!text.contains("Water snapshot failed")&&!text.contains("Underwater pass failed")
                    &&!text.contains("Round sun failed")&&!text.contains("Solar bloom failed"),"No water/sun runtime failures");
            if(text.contains("WATER CHECK 42-under-log.png"))check(text.contains("WATER CONTACT CHECK: sealed camera remains WATER"),"Sealed camera remains WATER");
        }
        System.out.println("PASS: water mask="+water+", SSR hits="+hits+", glass unaffected; native water/underwater captures verified.");
    }
    private static void verifyWaterline(Path dir)throws Exception {
        double[] previous=null;
        for(String name:new String[]{"24-waterline-below.png","25-waterline-contact.png","26-waterline-above.png"}) {
            BufferedImage frame=image(dir,name);double[] sky=new double[3],bed=new double[3];int count=0,bedCount=0,bad=0;
            for(int y=80;y<280;y++)for(int x=90;x<170;x++) {
                int rgb=frame.getRGB(x,y);int r=rgb>>16&255,g=rgb>>8&255,b=rgb&255;
                sky[0]+=r;sky[1]+=g;sky[2]+=b;count++;
                if(b>150&&r<60&&g<80)bad++;
            }
            for(int i=0;i<3;i++)sky[i]/=count;
            for(int y=500;y<680;y++)for(int x=470;x<620;x++) {
                int rgb=frame.getRGB(x,y);bed[0]+=rgb>>16&255;bed[1]+=rgb>>8&255;bed[2]+=rgb&255;bedCount++;
            }
            for(int i=0;i<3;i++)bed[i]/=bedCount;
            check(bad/(double)count<0.01&&sky[0]>90&&sky[1]>100,"Waterline sky/clouds must not turn saturated blue: "+name);
            check(bed[1]>bed[0]*1.2&&bed[2]>bed[0]*1.2,"Clipped near-plane water must still tint/absorb the seabed: "+name);
            if(previous!=null)for(int i=0;i<3;i++)check(Math.abs(previous[i]-sky[i])<20,"Sky must transition continuously across waterline");
            previous=sky;
        }
        int a=validProbePixels(image(dir,"30-probe-valid-a.png")),b=validProbePixels(image(dir,"31-probe-valid-b.png"));
        check(Math.min(a,b)>3000&&Math.min(a,b)/(double)Math.max(a,b)>0.35,"Two-degree pitch change must not discard the seabed probe: "+a+" / "+b);
        for(String name:new String[]{"29-probe-sparse.png","32-ssr-fast.png","33-ssr-quality.png","34-probe-fast.png","35-probe-quality.png"})image(dir,name);
        System.out.println("PASS: air/water near-plane sky and bed; persistent probe valid pixels "+a+" / "+b+"; fast/quality paths captured.");
    }
    private static int validProbePixels(BufferedImage frame) {
        int result=0;
        for(int y=75;y<650;y++)for(int x=0;x<1100;x++) {
            int rgb=frame.getRGB(x,y),r=rgb>>16&255,g=rgb>>8&255,b=rgb&255;
            if(Math.min(r,Math.min(g,b))>200&&Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<4)result++;
        }
        return result;
    }
    private static void verifyShoreAndBloom(Path dir)throws Exception {
        for(String name:new String[]{"53-shore-fast.png","55-shore-precise.png","56-shore-sky.png","57-shore-economy.png","59-shore-mask.png"})image(dir,name);
        for(String name:new String[]{"54-shore-fast-hit.png","58-shore-economy-hit.png"}) {
            BufferedImage mask=image(dir,name);int hit=0,total=0;
            // Solid roof panel, excluding real alpha gaps in reflected foliage
            // and the intentional fade at the screen boundary.
            for(int y=360;y<405;y++)for(int x=450;x<530;x++) {
                int rgb=mask.getRGB(x,y);
                if((rgb>>16&255)>200&&(rgb>>8&255)>200&&(rgb&255)>200)hit++;total++;
            }
            check(hit/(double)total>0.97,"Planar bank reflection must stay continuous in fast/economy mode: "+name+" "+hit/(double)total);
            System.out.printf("PASS: %s continuous hits %.3f%n",name,hit/(double)total);
        }
        BufferedImage off=image(dir,"60-sun-no-bloom.png"),on=image(dir,"61-sun-bloom.png"),strong=image(dir,"62-sun-strong.png");
        double near=ringDelta(on,off,40,80),wide=ringDelta(on,off,100,180),strongWide=ringDelta(strong,off,100,180);
        check(near>12&&wide>3&&strongWide>wide*1.3,"Visible solar bloom and its strength/spread controls: "+near+" / "+wide+" / "+strongWide);
        for(int radius:new int[]{60,100,160}) {
            double low=Double.POSITIVE_INFINITY,high=0;
            for(int i=0;i<8;i++) {
                int cx=(int)Math.round(550+radius*Math.cos(i*Math.PI/4)),cy=(int)Math.round(360+radius*Math.sin(i*Math.PI/4));
                double value=0;
                for(int y=cy-2;y<=cy+2;y++)for(int x=cx-2;x<=cx+2;x++)for(int shift:new int[]{0,8,16})
                    value+=Math.max(0,(on.getRGB(x,y)>>shift&255)-(off.getRGB(x,y)>>shift&255));
                value/=75;low=Math.min(low,value);high=Math.max(high,value);
            }
            check(low>1&&high/low<1.3,"Solar halo must be round, without a square blur footprint: "+radius+" "+high/low);
        }
        double blocked=regionDelta(image(dir,"63-sun-blocked-bloom.png"),image(dir,"64-sun-blocked-off.png"),380,200,720,520);
        check(blocked<0.5,"An occluded solar disk must not create bloom: "+blocked);
        System.out.printf("PASS: round solar bloom near/wide %.2f / %.2f RGB; strong %.2f; occluded delta %.3f%n",near,wide,strongWide,blocked);
    }
    private static double ringDelta(BufferedImage a,BufferedImage b,int inner,int outer) {
        double sum=0;int count=0;
        for(int y=100;y<650;y++)for(int x=100;x<1000;x++) {
            double radius=Math.hypot(x-550,y-360);
            if(radius<inner||radius>outer)continue;
            for(int shift:new int[]{0,8,16}){sum+=Math.max(0,(a.getRGB(x,y)>>shift&255)-(b.getRGB(x,y)>>shift&255));count++;}
        }
        return sum/Math.max(count,1);
    }
    private static double regionDelta(BufferedImage a,BufferedImage b,int left,int top,int right,int bottom) {
        double sum=0;int count=0;
        for(int y=top;y<bottom;y++)for(int x=left;x<right;x++)for(int shift:new int[]{0,8,16}) {
            sum+=Math.abs((a.getRGB(x,y)>>shift&255)-(b.getRGB(x,y)>>shift&255));count++;
        }
        return sum/Math.max(count,1);
    }
    private static void verifyContactDepthAndSun(Path dir)throws Exception {
        BufferedImage contact=image(dir,"41-log-contact.png");image(dir,"42-under-log.png");int blue=0;
        for(int y=358;y<374;y++)for(int x=410;x<690;x++) {
            int rgb=contact.getRGB(x,y),r=rgb>>16&255,g=rgb>>8&255,b=rgb&255;
            if(b>120&&b>g*1.25&&b>r*2)blue++;
        }
        check(blue<30,"No exposed sky-colored air slit under the submerged log: "+blue);
        int shallow=validProbePixels(image(dir,"43-deep-probe-a.png")),deep=validProbePixels(image(dir,"44-deep-probe-b.png"));
        int pitched=validProbePixels(image(dir,"45-deep-probe-pitch.png"));
        check(Math.min(shallow,Math.min(deep,pitched))>3000,"Mirror coverage must remain present at different depths/angles");
        compare(dir,"51-deep-reflection.png","52-deep-reflection-off.png",0.15,"Deep mirror still changes the scene");
        BufferedImage sun=image(dir,"46-round-sun.png");int count=0,minX=1100,maxX=0,minY=720,maxY=0;
        for(int y=260;y<460;y++)for(int x=450;x<650;x++) {
            int rgb=sun.getRGB(x,y);
            if((rgb>>16&255)>245&&(rgb>>8&255)>240&&(rgb&255)>225) {
                count++;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
            }
        }
        int width=maxX-minX+1,height=maxY-minY+1;
        double fill=count/(double)Math.max(width*height,1);
        check(count>300&&Math.abs(width-height)<5&&fill>0.65&&fill<0.9,"Solar core must be circular, centred on the shared sun direction: "+width+"x"+height+", fill "+fill);
        check(Math.abs((minX+maxX)*0.5-550)<8&&Math.abs((minY+maxY)*0.5-360)<8,"Sun and light shaft direction agree");
        image(dir,"47-solar-beams.png");
        BufferedImage low=image(dir,"48-beams-low.png"),high=image(dir,"49-beams-high.png"),off=image(dir,"50-beam-quality-off.png");
        double lowEnergy=0,highEnergy=0,residual=0;int pixels=0;
        for(int y=100;y<550;y++)for(int x=100;x<1000;x++)for(int shift:new int[]{0,8,16}) {
            int a=low.getRGB(x,y)>>shift&255,b=high.getRGB(x,y)>>shift&255,base=off.getRGB(x,y)>>shift&255;
            lowEnergy+=Math.max(a-base,0);highEnergy+=Math.max(b-base,0);residual+=Math.abs(a-b);pixels++;
        }
        check(highEnergy/pixels>0.5&&lowEnergy/highEnergy>0.65&&lowEnergy/highEnergy<1.35,"Low/high shaft integration should preserve beam energy");
        check(residual/pixels<3,"Continuous shaft integration must not create strong slices at low sample count: "+residual/pixels);
        System.out.printf("PASS: log slit pixels %d; probe coverage %d / %d / %d; circular solar core %dx%d fill %.3f; shaft low/high energy %.3f, residual %.3f%n",
                blue,shallow,deep,pitched,width,height,fill,lowEnergy/highEnergy,residual/pixels);
    }
    private static void verifyBeamsAndSeam(Path dir)throws Exception {
        BufferedImage grazing=image(dir,"28-probe-pitch-b.png");int warm=0;
        for(int y=400;y<500;y++)for(int x=0;x<1100;x++) {
            int rgb=grazing.getRGB(x,y),r=rgb>>16&255,g=rgb>>8&255,b=rgb&255;
            if(r>25&&r>g*1.1&&r>b*1.1)warm++;
        }
        check(warm<40,"Grazing submerged bank must not expose an unabsorbed wavy strip: "+warm);
        BufferedImage on=image(dir,"36-beams-on.png"),off=image(dir,"37-beams-off.png"),max=image(dir,"38-beams-max.png");
        double sum=0,strongSum=0;int count=0,lit=0;
        double[] differences=new double[900*450];
        for(int y=100;y<550;y++)for(int x=100;x<1000;x++) {
            double delta=0,strong=0;
            for(int shift:new int[]{0,8,16}) {
                int base=off.getRGB(x,y)>>shift&255;
                delta+=Math.max(0,(on.getRGB(x,y)>>shift&255)-base);
                strong+=Math.max(0,(max.getRGB(x,y)>>shift&255)-base);
            }
            delta/=3;strong/=3;differences[count++]=delta;sum+=delta;strongSum+=strong;
            if(delta>4)lit++;
        }
        java.util.Arrays.sort(differences);
        double low=differences[count/10],high=differences[count*9/10];
        check(high>6&&high-low>6,"Sunlight must form distinct beams with dark gaps, not uniform haze: "+low+" / "+high);
        check(lit>count*0.05&&lit<count*0.85,"Visible localized shaft coverage: "+lit+" / "+count);
        check(strongSum>sum*1.5,"Shaft brightness control must affect the volume");
        BufferedImage edge=image(dir,"39-waterline-beams.png"),edgeOff=image(dir,"40-waterline-no-beams.png");
        double skyDelta=0;int skyCount=0;
        for(int y=100;y<280;y++)for(int x=100;x<700;x++)for(int shift:new int[]{0,8,16}) {
            // Native sky/lightmap transitions may darken between these frames.
            // Shafts are additive: detect unwanted added light over the air sky.
            skyDelta+=Math.max(0,(edge.getRGB(x,y)>>shift&255)-(edgeOff.getRGB(x,y)>>shift&255));skyCount++;
        }
        check(skyDelta/skyCount<0.5,"Underwater shafts must not brighten the air sky at the waterline");
        System.out.printf("PASS: warm seam pixels %d; beam p10/p90 %.2f / %.2f, lit %.1f%%, mean %.2f, brightness ratio %.2f; waterline sky delta %.3f%n",
                warm,low,high,100.0*lit/count,sum/count,strongSum/Math.max(sum,1),skyDelta/skyCount);
    }
    private static void verifyUnderside(Path dir)throws Exception {
        BufferedImage mask=image(dir,"22-underwater-material.png");
        BufferedImage angle=image(dir,"17-grazing-max.png"),normal=image(dir,"18-grazing-adjacent.png");
        BufferedImage off=image(dir,"19-reflection-off.png"),maximum=image(dir,"20-reflection-max.png");
        BufferedImage noCaustics=image(dir,"21-surface-no-caustics.png");
        image(dir,"23-look-up.png");
        double angleDelta=0,switchDelta=0,strengthDelta=0,causticDelta=0;int count=0,dark=0;
        for(int y=75;y<650;y++)for(int x=0;x<1100;x++) {
            int material=mask.getRGB(x,y);
            if((material>>16&255)<=200||(material>>8&255)>=35||(material&255)<=200)continue;
            int rgb=normal.getRGB(x,y);
            if((rgb>>16&255)<16&&(rgb>>8&255)<58&&(rgb&255)<80)dark++;
            for(int shift:new int[]{0,8,16}) {
                int base=rgb>>shift&255;
                angleDelta+=Math.abs(base-(angle.getRGB(x,y)>>shift&255));
                switchDelta+=Math.abs(base-(off.getRGB(x,y)>>shift&255));
                strengthDelta+=Math.abs(base-(maximum.getRGB(x,y)>>shift&255));
                // Caustics add focused light. Native exposure/chat fading can
                // brighten the later OFF frame; that is not a caustic grid.
                causticDelta+=Math.max(0,base-(noCaustics.getRGB(x,y)>>shift&255));
            }
            count++;
        }
        check(count>100000,"Grazing underwater surface must be visible in the mask");
        check(dark/(double)count<0.15,"Surface must show sky/shore instead of the dark internal-reflection void: "+dark/(double)count);
        check(angleDelta/(3*count)<18,"A two-degree view change must not replace the entire surface");
        check(switchDelta/(3*count)>0.1&&strengthDelta/(3*count)>1,"Underwater reflection toggle and strength must both affect the image");
        check(causticDelta/(3*count)<1.5,"Max caustics must not paint a bright grid on the water/sky: "+causticDelta/(3*count));
        System.out.printf("PASS: underside dark fraction %.4f, angle delta %.3f, reflection toggle %.3f, max-caustic surface delta %.3f%n",
                dark/(double)count,angleDelta/(3*count),switchDelta/(3*count),causticDelta/(3*count));
    }
    private static void compare(Path dir,String a,String b,double threshold,String message)throws Exception {
        BufferedImage left=image(dir,a),right=image(dir,b);double total=0;int count=0;
        for(int y=100;y<650;y++)for(int x=100;x<1000;x++) {
            int p=left.getRGB(x,y),q=right.getRGB(x,y);
            for(int shift:new int[]{0,8,16}){total+=Math.abs((p>>shift&255)-(q>>shift&255));count++;}
        }
        double difference=total/count;check(difference>threshold,message+": "+difference);
        System.out.printf("PASS: %s, mean RGB delta %.3f/255%n",message,difference);
    }
    private static BufferedImage image(Path dir,String name)throws Exception {
        Path path=dir.resolve(name);check(Files.isRegularFile(path),"Missing capture "+name);
        BufferedImage image=ImageIO.read(path.toFile());check(image!=null&&image.getWidth()==1100&&image.getHeight()==720,"Image size: "+name);
        return image;
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
