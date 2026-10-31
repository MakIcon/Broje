package dev.broje.check;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Detects reflection flashes while allowing small physical image motion. */
public final class WaterMotionValidation {
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args[0]);
        for(String group:new String[]{"still","camera","waves"}) {
            int count=group.equals("still")?12:32;
            BufferedImage previous=read(directory,group,0);
            double worstFlip=0,worstResidual=0,worstRaw=0,totalRaw=0;
            for(int frame=1;frame<count;frame++) {
                BufferedImage current=read(directory,group,frame);
                int flips=0,pixels=0;double residual=0,raw=0;
                // Reflected roof, tree and stepped shore; avoid HUD and sky.
                for(int y=302;y<408;y++)for(int x=402;x<868;x++) {
                    int rgb=current.getRGB(x,y),best=765;
                    for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++)
                        best=Math.min(best,error(rgb,previous.getRGB(x+dx,y+dy)));
                    if(best>36)flips++;
                    residual+=best/3.0;raw+=error(rgb,previous.getRGB(x,y))/3.0;pixels++;
                }
                worstFlip=Math.max(worstFlip,flips/(double)pixels);
                worstResidual=Math.max(worstResidual,residual/pixels);
                worstRaw=Math.max(worstRaw,raw/pixels);totalRaw+=raw/pixels;
                previous=current;
            }
            if(worstFlip>0.005||worstResidual>0.5)
                throw new AssertionError(group+": reflection flashes: fraction="+worstFlip+", residual="+worstResidual);
            if(group.equals("still")&&worstRaw>0.15)
                throw new AssertionError("Frozen reflections changed: "+worstRaw);
            if(!group.equals("still")&&totalRaw/(count-1)<0.1)
                throw new AssertionError(group+": camera/waves did not actually move");
            System.out.printf(java.util.Locale.ROOT,"PASS: %s %d frames, max flash %.4f%%, residual %.4f/255, raw %.4f/255%n",
                    group,count,worstFlip*100,worstResidual,totalRaw/(count-1));
        }
    }
    private static BufferedImage read(Path directory,String group,int index)throws Exception {
        Path path=directory.resolve(String.format(java.util.Locale.ROOT,"%s-%02d.png",group,index));
        BufferedImage image=ImageIO.read(path.toFile());
        if(image==null||image.getWidth()!=1100||image.getHeight()!=720)
            throw new AssertionError("Missing or unexpected native frame: "+path);
        return image;
    }
    private static int error(int a,int b) {
        return Math.abs((a>>16&255)-(b>>16&255))+Math.abs((a>>8&255)-(b>>8&255))+Math.abs((a&255)-(b&255));
    }
}
