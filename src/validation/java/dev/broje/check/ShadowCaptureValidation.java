package dev.broje.check;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Image-level checks on native Vulkan captures, independent of shader implementation. */
public final class ShadowCaptureValidation {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        BufferedImage mask = image(directory, "03-mask.png");
        int shadowed = 0, lit = 0, filtered = 0;
        for (int y = 330; y < 525; y++) {
            for (int x = 200; x < 900; x++) {
                int pixel = mask.getRGB(x,y);
                int r = channel(pixel,16), g = channel(pixel,8), b = channel(pixel,0);
                if (r != g || g != b) continue;
                if (r > 240) shadowed++;
                if (r < 5) lit++;
                if (r > 25 && r < 230) filtered++;
            }
        }
        check(shadowed > 400 && lit > 10000, "The map must distinguish sunlit and shadowed floor");
        check(filtered > 100, "Shadow borders must have filtered intermediate coverage, not binary stair steps");
        double visible = difference(image(directory,"01-disabled.png"),image(directory,"02-shadows.png"),330,525);
        check(visible > 1, "Enabling shadows must visibly change the floor: " + visible);
        BufferedImage offscreen = image(directory,"08-offscreen-mask.png");
        int offscreenShadow = probe(offscreen,directory.resolve("offscreen-probes.txt"),0);
        check(offscreenShadow > 230, "Geometry outside the camera frustum must cast a shadow: " + offscreenShadow);
        BufferedImage contact = image(directory,"12-close-contact-mask.png");
        int atContact = probe(contact,directory.resolve("close-contact-probes.txt"),0);
        int nearContact = probe(contact,directory.resolve("close-contact-probes.txt"),1);
        check(atContact > 160 && nearContact > 230,
                "Low-sun shadow must stay attached 4 cm and 34 cm from the column: " + atContact + ", " + nearContact);
        double night = difference(image(directory,"09-night.png"),image(directory,"10-night-disabled.png"),330,525);
        check(night < 4, "Solar shadows must disappear at night: " + night);
        double fog = difference(image(directory,"11-close-contact.png"),image(directory,"13-shadows-and-fog.png"),330,525);
        check(fog > 1, "Fog must still work after the shadow pass: " + fog);
        double motion=0;
        BufferedImage previous=image(directory,"14-motion-0.png");
        for(int i=1;i<8;i++) {
            BufferedImage next=image(directory,"14-motion-"+i+".png");
            motion=Math.max(motion,difference(previous,next,380,500));
            previous=next;
        }
        check(motion<4,"Natural solar movement must not flash the shadow mask: "+motion);
        double grassMotion=0;
        previous=image(directory,"15-grass-0.png");
        for(int i=1;i<16;i++) {
            BufferedImage next=image(directory,"15-grass-"+i+".png");
            grassMotion=Math.max(grassMotion,difference(previous,next,120,400));
            previous=next;
        }
        check(grassMotion<4,"Grass shadows must remain stable under natural solar movement: "+grassMotion);
        double cameraMotion=verifyGrassCamera(directory);
        double entityShadow=difference(image(directory,"22-entities.png"),image(directory,"23-entities-off.png"),300,700);
        check(entityShadow>0.3,"Mobs, armor stands and dropped items must cast sun shadows: "+entityShadow);
        System.out.printf("PASS: real Vulkan sun shadows. Difference %.2f; %d shadowed, %d lit, %d filtered pixels; contact %d/%d; off-screen %d; night %.2f; fog %.2f; motion %.2f.%n",
                visible,shadowed,lit,filtered,atContact,nearContact,offscreenShadow,night,fog,motion);
        System.out.printf("PASS: grass shadows. Solar change %.3f/255; world-space floor change during camera movement %.3f/255.%n",
                grassMotion,cameraMotion);
        System.out.printf("PASS: entity shadows change the floor by %.2f/255.%n",entityShadow);
    }

    private static double verifyGrassCamera(Path directory) throws Exception {
        double[] previous=null;
        double maximum=0;
        for(int frame=0;frame<16;frame++) {
            BufferedImage capture=image(directory,"18-grass-camera-"+frame+".png");
            var points=Files.readAllLines(directory.resolve("18-grass-probes-"+frame+".txt"));
            double[] values=new double[points.size()];
            double sum=0;int count=0,flashes=0;
            for(int i=0;i<values.length;i++) {
                String[] xy=points.get(i).split(",");
                values[i]=floorValue(capture,Double.parseDouble(xy[0]),Double.parseDouble(xy[1]));
                if(previous==null || !Double.isFinite(values[i]) || !Double.isFinite(previous[i]))continue;
                double change=Math.abs(values[i]-previous[i]);
                sum+=change;count++;
                if(change>64)flashes++;
            }
            if(previous!=null) {
                check(count>1000,"Camera comparison must cover enough visible world-space floor points: "+count);
                maximum=Math.max(maximum,sum/count);
                check((double)flashes/count<0.001,"Grass shadows must not flash at fixed world positions: "+flashes+"/"+count);
            }
            previous=values;
        }
        check(maximum<0.5,"Moving the camera must not move grass shadows across the floor: "+maximum);
        return maximum;
    }

    private static double floorValue(BufferedImage image,double x,double y) {
        x-=0.5;y-=0.5; // The projection gives pixel-edge coordinates; samples lie at pixel centers.
        if(x<20 || x>=1080 || y<120 || y>=400)return Double.NaN;
        int ix=(int)x,iy=(int)y;
        // The floor diagnostic marks vertical grass surfaces magenta. Reject its
        // silhouette neighborhood too, so an occluding blade is not a floor probe.
        for(int dy=-2;dy<=3;dy++)for(int dx=-2;dx<=3;dx++) {
            int color=image.getRGB(ix+dx,iy+dy);
            if(channel(color,16)!=channel(color,8) || channel(color,8)!=channel(color,0))return Double.NaN;
        }
        double fx=x-ix,fy=y-iy;
        double top=channel(image.getRGB(ix,iy),16)*(1-fx)+channel(image.getRGB(ix+1,iy),16)*fx;
        double bottom=channel(image.getRGB(ix,iy+1),16)*(1-fx)+channel(image.getRGB(ix+1,iy+1),16)*fx;
        return top*(1-fy)+bottom*fy;
    }

    private static int probe(BufferedImage image, Path file, int index) throws Exception {
        String[] xy = Files.readAllLines(file).get(index).split(",");
        int pixel = image.getRGB(Integer.parseInt(xy[0]),Integer.parseInt(xy[1]));
        check(channel(pixel,16)==channel(pixel,8) && channel(pixel,8)==channel(pixel,0),
                "Contact probe must hit the grayscale floor mask: " + Integer.toHexString(pixel));
        return channel(pixel,16);
    }

    private static double difference(BufferedImage a, BufferedImage b, int fromY, int toY) {
        check(a.getWidth()==b.getWidth() && a.getHeight()==b.getHeight(),"Matching capture dimensions");
        double sum=0;int count=0;
        for(int y=fromY;y<toY;y++) for(int x=200;x<900;x++) {
            int p=a.getRGB(x,y),q=b.getRGB(x,y);
            for(int shift:new int[]{0,8,16}) sum+=Math.abs(channel(p,shift)-channel(q,shift));
            count+=3;
        }
        return sum/count;
    }
    private static int channel(int color,int shift){return (color>>>shift)&255;}
    private static BufferedImage image(Path directory,String name) throws Exception {
        BufferedImage image=ImageIO.read(directory.resolve(name).toFile());
        if(image==null)throw new AssertionError("Missing capture "+name);
        return image;
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
