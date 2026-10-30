package dev.broje.check;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Compares real rendered results, rather than mirroring the mixing implementation. */
public final class FeatureCaptureValidation {
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args[0]);
        double[] previous=null;
        double maximum=0;
        int bodyPixels=0;
        double[] off=floor(directory,"feature-no-body");
        for(int frame=0;frame<8;frame++) {
            double[] current=floor(directory,"feature-still-"+frame);
            double difference=0;int count=0;
            for(int i=0;i<current.length;i++) {
                if(Double.isFinite(current[i])&&Double.isFinite(off[i])&&current[i]-off[i]>128)bodyPixels++;
                if(previous==null||!Double.isFinite(current[i])||!Double.isFinite(previous[i]))continue;
                difference+=Math.abs(current[i]-previous[i]);count++;
            }
            if(previous!=null) {
                check(count>1000,"Enough visible world-space floor probes");
                maximum=Math.max(maximum,difference/count);
            }
            previous=current;
        }
        check(bodyPixels>30,"The mob must cast a real silhouette away from its body, not just a contact disk: "+bodyPixels);
        check(maximum<0.5,"A frozen mob's shadow must stay still as the camera moves: "+maximum);
        double[][] real=colors(directory,"feature-glass-realistic"), bright=colors(directory,"feature-glass-bright"),
                overlap=colors(directory,"feature-glass-overlap"),grey=colors(directory,"feature-glass-grey"),
                vivid=colors(directory,"feature-glass-vivid");
        check(mean(bright[1])>mean(real[1])+15,"Mixed colored light must stay brighter than physical absorption");
        check(bright[1][0]>bright[1][1]+10&&bright[1][2]>bright[1][1]+10,"Red plus blue must produce purple");
        check(mean(overlap[1])>mean(bright[1])+25&&mean(overlap[4])>mean(bright[4])+25,"Overlap brightness must lift both mixed colors");
        check(distance(overlap[0],bright[0])<2&&distance(overlap[2],bright[2])<2,"Overlap brightness must preserve single-pane patches");
        for(double[] color:grey)check(Math.max(color[0],Math.max(color[1],color[2]))-Math.min(color[0],Math.min(color[1],color[2]))<3,"Zero saturation must make glass light neutral");
        check(mean(vivid[0])>mean(bright[0])+15,"Brightness control must visibly lift a single pane");
        System.out.printf("PASS: body silhouette probes %d; world-space camera stability %.3f/255; bright overlap %.2f vs physical %.2f; independent overlap/brightness/saturation controls.%n",
                bodyPixels,maximum,mean(bright[1]),mean(real[1]));
    }

    private static double[] floor(Path directory,String name)throws Exception {
        BufferedImage image=ImageIO.read(directory.resolve(name+".png").toFile());
        var points=Files.readAllLines(directory.resolve(name+".txt"));double[] values=new double[points.size()];
        for(int i=0;i<values.length;i++) {
            String[] xy=points.get(i).split(",");double x=Double.parseDouble(xy[0])-0.5,y=Double.parseDouble(xy[1])-0.5;
            values[i]=Double.NaN;
            // Exclude the cow and the floor it can occlude as the camera moves.
            double wx=-6+(i%97)*0.125,wz=-3+(i/97)*0.125;
            if(wx>-1&&wx<1.5&&wz>-1&&wz<1.5)continue;
            if(x<5||y<80||x>=image.getWidth()-5||y>=image.getHeight()-5)continue;
            int ix=(int)x,iy=(int)y;boolean neutral=true;
            for(int dy=-1;dy<=2;dy++)for(int dx=-1;dx<=2;dx++) {
                int p=image.getRGB(ix+dx,iy+dy);if(Math.abs(channel(p,16)-channel(p,8))>1||Math.abs(channel(p,8)-channel(p,0))>1)neutral=false;
            }
            if(!neutral)continue;
            double fx=x-ix,fy=y-iy;
            double top=channel(image.getRGB(ix,iy),16)*(1-fx)+channel(image.getRGB(ix+1,iy),16)*fx;
            double bottom=channel(image.getRGB(ix,iy+1),16)*(1-fx)+channel(image.getRGB(ix+1,iy+1),16)*fx;
            values[i]=top*(1-fy)+bottom*fy;
        }
        return values;
    }
    private static double[][] colors(Path directory,String name)throws Exception {
        var image=ImageIO.read(directory.resolve(name+".png").toFile());var points=Files.readAllLines(directory.resolve(name+".txt"));
        double[][] result=new double[points.size()][3];
        for(int i=0;i<result.length;i++) {
            var xy=points.get(i).split(",");int x=(int)Double.parseDouble(xy[0]),y=(int)Double.parseDouble(xy[1]);
            for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++)for(int c=0;c<3;c++)result[i][c]+=channel(image.getRGB(x+dx,y+dy),16-8*c)/25.0;
        }
        return result;
    }
    private static int channel(int rgb,int shift){return(rgb>>>shift)&255;}
    private static double mean(double[] color){return(color[0]+color[1]+color[2])/3;}
    private static double distance(double[] a,double[] b){return Math.abs(a[0]-b[0])+Math.abs(a[1]-b[1])+Math.abs(a[2]-b[2]);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
