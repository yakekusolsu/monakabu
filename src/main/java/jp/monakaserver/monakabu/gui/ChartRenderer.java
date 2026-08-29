package jp.monakaserver.monakabu.gui;

import java.math.BigDecimal;
import java.util.List;

public final class ChartRenderer {
    private static final char[] BARS="▁▂▃▄▅▆▇█".toCharArray();
    private ChartRenderer(){}
    public static String render(List<BigDecimal> prices,int points){
        if(prices.isEmpty())return "データなし";int count=Math.min(points,prices.size());double min=Double.MAX_VALUE,max=-Double.MAX_VALUE;
        double[] sampled=new double[count];for(int i=0;i<count;i++){int index=(int)Math.floor((double)i*prices.size()/count);double value=prices.get(Math.min(index,prices.size()-1)).doubleValue();sampled[i]=value;min=Math.min(min,value);max=Math.max(max,value);}
        StringBuilder result=new StringBuilder(count);for(double value:sampled){int level=max==min?3:(int)Math.round((value-min)/(max-min)*(BARS.length-1));result.append(BARS[Math.max(0,Math.min(BARS.length-1,level))]);}return result.toString();
    }
}
