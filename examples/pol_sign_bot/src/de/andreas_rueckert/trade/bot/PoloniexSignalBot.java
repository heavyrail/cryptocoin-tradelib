/**
 * Java implementation for cryptocoin trading.
 *
 * Copyright (c) 2013 the authors:
 * 
 * @author Andreas Rueckert <mail@andreas-rueckert.de>
 *
 * Permission is hereby granted, free of charge, to any person obtaining 
 * a copy of this software and associated documentation files (the "Software"), 
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, 
 * and/or sell copies of the Software, and to permit persons to whom the Software
 * is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all 
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, 
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A 
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT 
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION 
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE 
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package de.andreas_rueckert.trade.bot;

import de.andreas_rueckert.trade.*;
import de.andreas_rueckert.trade.order.*;
import de.andreas_rueckert.trade.account.TradeSiteAccount;
import de.andreas_rueckert.trade.chart.ChartProvider;
import de.andreas_rueckert.trade.chart.ChartAnalyzer;
import de.andreas_rueckert.trade.site.TradeSite;
import de.andreas_rueckert.trade.site.TradeSiteUserAccount;
import de.andreas_rueckert.util.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;
import java.io.*;

import org.apache.log4j.Logger;
import org.apache.log4j.Level;

import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;

import org.jibble.simplewebserver.*;

/**
 * This is a simple Poloniex proxy bot 
 */
public class PoloniexSignalBot
{

    // Static variables

    /**
     * The interval to update the bot activities.
     */
    private final static int UPDATE_INTERVAL = 60;  // 60 seconds for now...
    
    /* tune these 3 numbers! */
    private final static int EMA_SHORT_INTERVALS_NUM = 12;
    private final static int EMA_LONG_INTERVALS_NUM = 26;
    private final static int MACD_EMA_INTERVALS_NUM = 9;
    
    private final static long MACD_EMA_TIME_PERIOD = 60L * 1000000L; // one minute - must correspond to next line!
    private final static long MACD_EMA_INTERVAL_MICROS = MACD_EMA_INTERVALS_NUM * MACD_EMA_TIME_PERIOD;
    // Magic below! add 1 (one) extra period to avoid edge effects while calculating MACD
    // i.e. to calculate 9m-EMA of MACD we take 10m-interval for 9 MACD values to be guaranteedly included in the calculation
    private final static String MACD_EMA_INTERVAL = Integer.toString(MACD_EMA_INTERVALS_NUM + 1) + "m"; // here and below 'm' means minute
    
    private final static String EMA_SHORT_INTERVAL = EMA_SHORT_INTERVALS_NUM + "m";
    private final static long EMA_SHORT_INTERVAL_MICROS = MACD_EMA_TIME_PERIOD * EMA_SHORT_INTERVALS_NUM;
    private final static String EMA_LONG_INTERVAL = EMA_LONG_INTERVALS_NUM + "m";
    private final static long EMA_LONG_INTERVAL_MICROS = MACD_EMA_TIME_PERIOD * EMA_LONG_INTERVALS_NUM;

    private final static String DOMAIN = "www.poloniex.com";
    private final static String API_URL_INFO = "https://" + DOMAIN + "/public?command=returnTicker";
    private final static String WWW_ROOT = "./www";

    private final static BigDecimal THOUSAND = new BigDecimal("1000");

    // Instance variables

    private static HashMap<String, TradeArchive> tradeArchives;
    private static HashMap<String, TradeArchive> macdArchives;
    private static ArrayList<Trade> macdCache;
    private static TimeUtils timeUtils;
    private static Logger logger;
    private static DecimalFormat priceFormat;
    private static DecimalFormat macdFormat;
    private static DecimalFormat relMacdFormat;
    private static ChartAnalyzer analyzer;
    private static ChartProvider provider;
    private static BigDecimal lastRelMacd;
    private static File wwwRoot;
   
    // Methods
    
    private static CurrencyPairImpl makeCurrencyPair(String pair)
    {
        String currencyDetail[] = pair.split("_");
        String currency = currencyDetail[1].toUpperCase();
        String paymentCurrency = currencyDetail[0].toUpperCase();
        CurrencyImpl currencyObject = CurrencyImpl.findByString(currency);
        CurrencyImpl paymentCurrencyObject = CurrencyImpl.findByString(paymentCurrency);
        if (currencyObject != null && paymentCurrencyObject != null)
        {
            return new CurrencyPairImpl(currencyObject, paymentCurrencyObject);
        }
        else
        {
            return null;
        }
    }

    private static int updateTradeArchives() 
    {
        String requestResult = HttpUtils.httpGet(API_URL_INFO);
        int result = 0;
        if (requestResult != null) 
        {
            JSONObject jsonResult = JSONObject.fromObject(requestResult);
            JSONObject output = new JSONObject();
            Iterator itPairs = ((JSONObject) jsonResult).keys();
            long now = timeUtils.getCurrentGMTTimeMicros();
            while (itPairs.hasNext())
            {
                String pair = (String) itPairs.next();
                Price price = new Price((String) jsonResult.get(pair));
                CurrencyPairImpl currencyPair = makeCurrencyPair(pair);
                if (currencyPair != null)
                {
                    SimpleTrade t = new SimpleTrade(price, now);
                    TradeArchive archive = tradeArchives.get(pair);
                    if (archive == null)
                    {
                        archive = new TradeArchive(EMA_LONG_INTERVALS_NUM);
                        for (int i = 0; i < EMA_LONG_INTERVALS_NUM; i++)
                        {
                            archive.add(t);
                        }
                        tradeArchives.put(pair, archive);
                    }
                    else
                    {
                        archive.add(t);
                    }
                    Trade trades[] = archive.toArray(new Trade[0]);
                    Price shortEma = analyzer.ema(trades, EMA_SHORT_INTERVAL);
                    Price longEma = analyzer.ema(trades, EMA_LONG_INTERVAL);
                    Price macdLine = shortEma.subtract(longEma);
                    t = new SimpleTrade(macdLine, now);
                    TradeArchive macdArchive = macdArchives.get(pair);
                    if (macdArchive == null)
                    {
                        macdArchive = new TradeArchive(MACD_EMA_INTERVALS_NUM);
                        for (int i = 0; i < MACD_EMA_INTERVALS_NUM; i++)
                        {
                            macdArchive.add(t);
                        }
                        macdArchives.put(pair, macdArchive);
                    }
                    else
                    {
                        macdArchive.add(t);
                    }
                    Trade[] macdCache = macdArchive.toArray(new Trade[0]);
                   
                    Price macdSignalLine = analyzer.ema(macdCache, MACD_EMA_INTERVAL);
                    Price macd = macdLine.subtract(macdSignalLine);
                    BigDecimal relMacd = macd.divide(price, MathContext.DECIMAL128).multiply(THOUSAND);
                    BigDecimal deltaRelMacd = relMacd.subtract(lastRelMacd);
                    lastRelMacd = relMacd;

                    JSONArray pairInfo = new JSONArray();
                    pairInfo.add(priceFormat.format(price));
                    pairInfo.add(macdFormat.format(macd));
                    pairInfo.add(relMacdFormat.format(relMacd));
                    pairInfo.add(relMacdFormat.format(deltaRelMacd));
                    output.put(pair, pairInfo);
                    if (currencyPair.toString().equals("GRC<=>BTC") ||
                            currencyPair.toString().equals("EMC2<=>BTC") ||
                            currencyPair.toString().equals("MYR<=>BTC") ||
                            currencyPair.toString().equals("XCP<=>BTC"))
                    {
                        logger.info(currencyPair + " " + priceFormat.format(price) + " " + macdFormat.format(macd));
                    }
                }
                result++;
            }
            try
            {
                PrintWriter pw = new PrintWriter(wwwRoot + File.separator + "macd");
                pw.println(output);
                pw.flush();
            }
            catch (FileNotFoundException e)
            {
                logger.error(e);
            }
        }
        return result;
    }

    private static void sleepUntilNextCycle(long t1, int numPairs)
    {
        long t2 = System.currentTimeMillis();
        long deltaT = t2 - t1;
        logger.info("It took " + deltaT + " ms to analyze " + numPairs + " pairs. Now going to sleep...");
        long sleepTime = (UPDATE_INTERVAL * 1000); 
        if (sleepTime > 0)
        {
	        try 
            {
                Thread.sleep(sleepTime);  // Wait for the next loop.
            } 
            catch( InterruptedException ie) 
            {
                System.err.println( "Ticker or depth loop sleep interrupted: " + ie.toString());
            }
        }
    }

    public static void main(String[] args)
    {
        wwwRoot = new File(WWW_ROOT);
        if (!wwwRoot.exists())
        {
            wwwRoot.mkdirs();
        }
        try
        {
            new SimpleWebServer(wwwRoot, 54321);
        }
        catch (IOException e)
        {
            logger.error(e);
        }
        tradeArchives = new HashMap<String, TradeArchive>();
        macdArchives = new HashMap<String, TradeArchive>();
        macdCache = new ArrayList<Trade>();  
        analyzer = ChartAnalyzer.getInstance(); 
        provider = ChartProvider.getInstance();
        timeUtils = TimeUtils.getInstance();
        priceFormat = new DecimalFormat("###0.00000000", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        macdFormat = new DecimalFormat("+##0.00000000000;-##0.00000000000", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        relMacdFormat = new DecimalFormat("+###.###;-###.###", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        lastRelMacd = new BigDecimal("0");
        logger = LogUtils.getInstance().getLogger();
        logger.setLevel(Level.INFO);
        logger.info("PoloniexSignalBot started");
       
        // Create a ticker thread.
        new Thread() 
        {

            /**
            * The main bot thread.
            */
            @Override public void run() 
            {
                int numPairs = 0;
                while (true) 
                { 
                    long t1 = System.currentTimeMillis();
                    try
                    {
                        numPairs = updateTradeArchives();
                    }
                    catch (Exception e)
                    {
                        logger.error(e);
                        e.printStackTrace();
                    }
                    finally
                    {
                        sleepUntilNextCycle(t1, numPairs);
                    } 
		        }
		    }
 	    }.start();  // Start the update thread.
    }
    
    static class SimpleTrade implements Trade
    {

        Price price;
        long timestamp;

        public SimpleTrade(Price p, long t)
        {
            price = p;
            timestamp = t;
        }

        public Amount getAmount()
        {
            return null;
        }

        public String getId()
        {
            return null;
        }

        public Price getPrice()
        {
            return price;
        }
                    
        public long getTimestamp()
        {
            return timestamp;
        }
                    
        public TradeType getType()
        {
            return null;
        }
 
    }

    static class TradeArchive extends ArrayList<Trade>
    {
        private int maxCapacity;

        public TradeArchive(int capacity)
        {
            maxCapacity = capacity;
        }

        public boolean add(Trade e)
        {
            if (size() == maxCapacity)
            {
                remove(0);
            }
            return super.add(e);
        }

        public void add(int index, Trade e)
        {
            if (size() == maxCapacity)
            {
                remove(0);
            }
            super.add(index, e);
        }

    }

}
