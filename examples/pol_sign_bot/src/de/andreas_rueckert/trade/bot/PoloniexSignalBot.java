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

import de.andreas_rueckert.trade.site.poloniex.client.PoloniexCurrencyImpl;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;
import java.io.*;
import java.text.*;

import org.apache.log4j.Logger;
import org.apache.log4j.Level;

import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

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
    private final static String API_URL_VOL = "https://" + DOMAIN + "/public?command=return24hVolume";
    private final static String API_URL_TRADES = "https://" + DOMAIN + "/public?command=returnTradeHistory&currencyPair=";
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
    private static AbstractCollection<String> hotCollection;
   
    // Methods
    
    private static CurrencyPairImpl makeCurrencyPair(String pair)
    {
        String currencyDetail[] = pair.split("_");
        String currency = currencyDetail[1].toUpperCase();
        String paymentCurrency = currencyDetail[0].toUpperCase();
        PoloniexCurrencyImpl currencyObject = PoloniexCurrencyImpl.findByString(currency);
        PoloniexCurrencyImpl paymentCurrencyObject = PoloniexCurrencyImpl.findByString(paymentCurrency);
        if (currencyObject != null && paymentCurrencyObject != null)
        {
            return new CurrencyPairImpl(currencyObject, paymentCurrencyObject);
        }
        else
        {
            return null;
        }
    }

    private static JSONArray sortJsonObject(JSONObject obj, final String field)
    {
        List<JSONObject> jsons = new ArrayList<JSONObject>();
        for (Iterator it = obj.keys(); it.hasNext(); )
        {
            String key = (String) it.next();
            if (key.startsWith(field))
            {
                Object rec = obj.get(key);
                if (rec instanceof JSONObject)
                {
                    jsons.add((JSONObject) rec);
                }
            }
        }
        Collections.sort(jsons, new Comparator<JSONObject>()
        {
            @Override
            public int compare(JSONObject lhs, JSONObject rhs)
            {
                if (!lhs.containsKey(field))
                {
                    return 1;
                }
                else 
                {
                    if (!rhs.containsKey(field))
                    {
                        return -1;
                    }
                    else
                    {
                        String lid = lhs.getString(field);
                        String rid = rhs.getString(field);
                        return (new Double(rid)).compareTo(new Double(lid));
                    }
                }
            }
        });
        return JSONArray.fromObject(jsons);
    }

    private static int updateTradeArchives(AbstractCollection<String> hotCollection) 
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
                    Trade[] trades = updateTradeArchive(pair, new SimpleTrade(price, now));
                    Price shortEma = analyzer.ema(trades, EMA_SHORT_INTERVAL);
                    Price longEma = analyzer.ema(trades, EMA_LONG_INTERVAL);
                    Price macdLine = shortEma.subtract(longEma);
                    Trade[] macdCache = updateMacdArchive(pair, new SimpleTrade(macdLine, now));                    
                    Price macdSignalLine = analyzer.ema(macdCache, MACD_EMA_INTERVAL);
                    Price macd = macdLine.subtract(macdSignalLine);
                    BigDecimal relMacd = 
                            price.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : macd.divide(price, MathContext.DECIMAL128).multiply(THOUSAND);
                    BigDecimal deltaRelMacd = relMacd.subtract(lastRelMacd);
                    lastRelMacd = relMacd;
                    JSONArray pairInfo = new JSONArray();
                    pairInfo.add(priceFormat.format(price));
                    pairInfo.add(macdFormat.format(macd));
                    pairInfo.add(relMacdFormat.format(relMacd));
                    pairInfo.add(relMacdFormat.format(deltaRelMacd));
                    output.put(pair, pairInfo);
                    if (hotCollection.contains(pair))
                    {
                        logger.info(currencyPair + " " + priceFormat.format(price) + " " + macdFormat.format(macd) + " " + relMacdFormat.format(relMacd));
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

    private static Trade[] filterTrades(Trade[] trades, long from, long to)
    {
        ArrayList<Trade> resultBuffer = new ArrayList<Trade>();
        for (int i = 0; i < trades.length; ++i) 
        {
            if (trades[i].getTimestamp() > from && trades[i].getTimestamp() <= to) 
            {
                resultBuffer.add(trades[i]);
            }
        }
        return resultBuffer.toArray(new Trade[resultBuffer.size()]);
    }

    private static Trade[] getTradesFromSite(String pair) 
    {
        String url = API_URL_TRADES + pair;
        ArrayList<Trade> trades = new ArrayList<Trade>();
        String requestResult = HttpUtils.httpGet(url);

        if (requestResult != null) 
        {  // If the HTTP request worked ok.
            try 
            {
                // Convert the result to an JSON array.
                JSONArray resultArray = JSONArray.fromObject(requestResult);
        
                // Iterate over the array and convert each trade from json to a Trade object.
                for (int i = 0; i < resultArray.size(); i++) 
                {
                    JSONObject tradeObject = resultArray.getJSONObject(i);
                    trades.add(new SimpleTrade(tradeObject));  // Add the new Trade object to the list.
                }
                Trade[] tradeArray = trades.toArray(new Trade[trades.size()]);  // Convert the list to an array.
                return tradeArray;  // And return the array.
            }
            catch (JSONException je) 
            {
                System.err.println("Cannot parse trade object: " + je.toString());
            }
        }
        return null;  // An error occured.
    }

    private static Trade[] updateTradeArchive(String pair, SimpleTrade trade)
    {
       TradeArchive archive = tradeArchives.get(pair);
       if (archive == null)
       {
           archive = initializeTradeArchive(trade);
           tradeArchives.put(pair, archive);
       }
       else
       {
           archive.add(trade);
       }
       return archive.toArray(new Trade[0]);
    }

    private static Trade[] updateMacdArchive(String pair, SimpleTrade trade)
    {
        TradeArchive macdArchive = macdArchives.get(pair);
        if (macdArchive == null)
        {
            macdArchive = initializeMacdArchive(trade);
            macdArchives.put(pair, macdArchive);
        }
        else
        {
            macdArchive.add(trade);
        }
        return macdArchive.toArray(new Trade[0]);
    }

    private static TradeArchive initializeTradeArchive(SimpleTrade trade)
    {
        TradeArchive result = new TradeArchive(EMA_LONG_INTERVALS_NUM);
        for (int i = 0; i < EMA_LONG_INTERVALS_NUM; i++)
        {
            result.add(trade);
        }
        return result;
    }

    private static TradeArchive initializeMacdArchive(SimpleTrade trade)
    {
        TradeArchive result = new TradeArchive(MACD_EMA_INTERVALS_NUM);
        for (int i = 0; i < MACD_EMA_INTERVALS_NUM; i++)
        {
            result.add(trade);
        }
        return result;
    }

    private static JSONArray makeHotList(String base)
    {
        String requestResult = HttpUtils.httpGet(API_URL_VOL);
        int result = 0;
        if (requestResult != null)
        {
            JSONObject jsonResult = JSONObject.fromObject(requestResult);
            JSONArray output = sortJsonObject(jsonResult, base);
            try
            {
                PrintWriter pw = new PrintWriter(wwwRoot + File.separator + "hot_" + base);
                pw.println(output);
                pw.flush();
            }
            catch (FileNotFoundException e)
            {
                logger.error(e);
            }
            finally
            {
                return output;
            }
        }
        else
        {
            return null;
        }
    }
 
    private static JSONArray truncateJsonArray(JSONArray src, int limit)
    {
        JSONArray result = new JSONArray();
        for (int i = 0; i < limit; i++)
        {
            result.add(src.get(i));
        }
        return result;
    }

    private static void makeHotCollection(JSONArray src, AbstractCollection<String> result)
    {
        for (Iterator it = src.iterator(); it.hasNext(); )
        {
            JSONObject rec = (JSONObject) it.next();
            JSONArray keys = rec.names();
            String pair = keys.get(0) + "_" + keys.get(1);
            result.add(pair);
        }
    }

    private static void sleepUntilNextCycle(long t1, int numPairs)
    {
        long deltaT = System.currentTimeMillis() - t1;
        logger.info("It took " + deltaT + " ms to analyze " + numPairs + " pairs.");
        logger.info("Now going to sleep...");
        deltaT = System.currentTimeMillis() - t1;
        long sleepTime = UPDATE_INTERVAL * 1000 - deltaT; 
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

    private static AbstractCollection<String> updateHotCollection()
    {
        AbstractCollection<String> result = new ArrayList<String>();
        JSONArray hotBtc = makeHotList("BTC");
        JSONArray hotLtc = makeHotList("LTC");
        hotBtc = truncateJsonArray(hotBtc, 5);
        hotLtc = truncateJsonArray(hotLtc, 3);
        makeHotCollection(hotBtc, result);
        makeHotCollection(hotLtc, result);
        return result;
    }

    private static void initialize()
    {
        logger = LogUtils.getInstance().getLogger();
        logger.setLevel(Level.INFO);
        logger.info("PoloniexSignalBot started");
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
        hotCollection = updateHotCollection();
        for (Iterator it = hotCollection.iterator(); it.hasNext(); )
        {
            String pair = (String) it.next();
            fillArchives(pair);
        }
    }

    private static void fillArchives(String pair)
    {
        //logger.info(pair + ": getting data");
        int totalTrades = EMA_LONG_INTERVALS_NUM + MACD_EMA_INTERVALS_NUM;
        TradeArchive archive = new TradeArchive(totalTrades);
        Trade[] data = getTradesFromSite(pair);
        Price lastPrice = data[data.length - 1].getPrice();
        //logger.info(pair + ": data received");
        long now = timeUtils.getCurrentGMTTimeMicros();  
        //logger.info(pair + ": now = " + now);
        long to = now - totalTrades * MACD_EMA_TIME_PERIOD;
        long from;
        for (int i = 0; i < totalTrades; i++)
        {
            from = to - MACD_EMA_TIME_PERIOD;
            //logger.info(pair + ": " + i + ": (" + from + "; " + to + "]");
            Trade[] slice = filterTrades(data, from, to);
            //logger.info(pair + ": " + slice.length + " trades");
            if (slice.length > 0)
            {
                archive.add(slice[slice.length - 1]);
            }
            else
            {
                archive.add(new SimpleTrade(null, now));
            }
            to += MACD_EMA_TIME_PERIOD;
        }
        for (int i = totalTrades - 1; i >= 0; i--)
        {
            Trade t = archive.get(i);
            Price currentPrice = t.getPrice();
            if (currentPrice == null)
            {
                archive.set(i, new SimpleTrade(lastPrice, t.getTimestamp()));
            }
            else
            {
                lastPrice = currentPrice;
            }
        }
        tradeArchives.put(pair, archive);
        TradeArchive macdArchive = new TradeArchive(MACD_EMA_INTERVALS_NUM);

        long startTime = now - MACD_EMA_INTERVAL_MICROS;
        for (int i = MACD_EMA_INTERVALS_NUM; i > 0; i--)
        {
            for (int j = 0; j < archive.size(); j++)
            {
                System.out.print(archive.get(j).getPrice() + ", ");
            }
            Trade[] archiveData = archive.toArray(new Trade[archive.size()]);
            System.out.println(pair + archive.size() + "!!!!!!!!!!!!!!!!");
            Price shortEma = analyzer.ema(archiveData, startTime - EMA_SHORT_INTERVAL_MICROS, startTime, MACD_EMA_TIME_PERIOD);
            System.out.println(pair + archive.size() + "!!!!!!!!!!!!!!!!???");
            Price longEma = analyzer.ema(archiveData, startTime - EMA_LONG_INTERVAL_MICROS, startTime, MACD_EMA_TIME_PERIOD);
            Price macdLine = shortEma.subtract(longEma);            
            macdArchive.add(new SimpleTrade(macdLine, startTime));
            logger.info(pair + ": " + (MACD_EMA_INTERVALS_NUM - i) + " : " + macdLine);
            startTime += MACD_EMA_TIME_PERIOD;
        }

        macdArchives.put(pair, macdArchive);
    }

    public static void main(String[] args)
    {
        initialize();
        new Thread() 
        {
            @Override
            public void run() 
            {
                int numPairs = 0;
                while (true) 
                { 
                    long t1 = System.currentTimeMillis();
                    try
                    {
                        hotCollection = updateHotCollection();
                        numPairs = updateTradeArchives(hotCollection);
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

        public SimpleTrade(JSONObject jsonTrade)
        {
            try 
            {
                DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
                Date date = dateFormat.parse(jsonTrade.getString("date") + " GMT");
                timestamp = date.getTime() * 1000L;
            }
            catch( Exception e) 
            {
                throw new NumberFormatException("Date is not in a proper format");
            }

            try 
            {
                price = new Price(jsonTrade.getString("rate"));
            }
            catch( JSONException je)
            {
                throw new NumberFormatException("Price is not in proper decimal format");
            }
        
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
