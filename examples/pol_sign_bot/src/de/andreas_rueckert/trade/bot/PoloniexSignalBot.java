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
import java.io.IOException;
import java.io.File;

import org.apache.log4j.Logger;
import org.apache.log4j.Level;

import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;

//import de.andreas_rueckert.persistence.PersistentProperty;
//import de.andreas_rueckert.persistence.PersistentPropertyList;

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

    private final static BigDecimal THOUSAND = new BigDecimal("1000");

    // Instance variables

    private static HashMap<String, TradeArchive> tradeArchives;
    private static HashMap<String, TradeArchive> macdArchives;
    private static ArrayList<Trade> macdCache;
    private static Price shortEma;
    private static JSONObject macdStorage;
    private static Price longEma;
    private static BigDecimal macd;
    private static Price macdLine;
    private static BigDecimal macdSignalLine;
    private static BigDecimal lastMacd;
    private static BigDecimal deltaMacd;
    private static BigDecimal relMacd;
    private static ChartAnalyzer analyzer;
    private static ChartProvider provider;
    private static TimeUtils timeUtils;
    private static Logger logger;
    private static DecimalFormat priceFormat;
    private static DecimalFormat macdFormat;

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

    private static boolean updateTradeArchives() 
    {
        String requestResult = HttpUtils.httpGet(API_URL_INFO);
        if (requestResult != null) 
        {
            JSONObject jsonResult = JSONObject.fromObject(requestResult);
            macdStorage = new JSONObject();
            Iterator itPairs = ((JSONObject) jsonResult).keys();
            String pair;
            Price price;
            CurrencyPairImpl currencyPair;
            long now = timeUtils.getCurrentGMTTimeMicros();
            long startTime = now - MACD_EMA_INTERVAL_MICROS;
            while (itPairs.hasNext())
            {
                pair = (String) itPairs.next();
                price = new Price((String) jsonResult.get(pair));
                currencyPair = makeCurrencyPair(pair);
                if (currencyPair != null)
                {
                    SimpleTrade t = new SimpleTrade(price, now);
                    TradeArchive archive = tradeArchives.get(pair);
                    if (archive == null)
                    {
                        archive = new TradeArchive(EMA_LONG_INTERVALS_NUM);
                        logger.info("initializing trade archive for " + currencyPair);
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
                    /*if (trades.length >= EMA_SHORT_INTERVALS_NUM)
                    {
                        shortEma = analyzer.ema(trades, startTime - EMA_SHORT_INTERVAL_MICROS, startTime, MACD_EMA_TIME_PERIOD);
                        if (trades.length >= EMA_LONG_INTERVALS_NUM)
                        {
                            longEma = analyzer.ema(trades, startTime - EMA_LONG_INTERVAL_MICROS, startTime, MACD_EMA_TIME_PERIOD);
                        }
                        else
                        {
                            longEma = price;
                        }
                    }
                    else
                    {
                        shortEma = price;
                        longEma = price;
                    }*/


                    shortEma = analyzer.ema(trades, EMA_SHORT_INTERVAL);
                    longEma = analyzer.ema(trades, EMA_LONG_INTERVAL);
                    
                    macdLine = shortEma.subtract(longEma);
                    t = new SimpleTrade(macdLine, now);
                    TradeArchive macdCache = macdArchives.get(pair);
                    if (macdCache == null)
                    {
                        macdCache = new TradeArchive(MACD_EMA_INTERVALS_NUM);
                        logger.info("initializing macd cache for " + currencyPair);
                        for (int i = 0; i < MACD_EMA_INTERVALS_NUM; i++)
                        {
                            macdCache.add(t);
                        }
                        macdArchives.put(pair, macdCache);
                    }
                    else
                    {
                        macdCache.add(t);
                    }
                    Trade[] cache = macdCache.toArray(new Trade[0]);
                   
                    macdSignalLine = analyzer.ema(cache, MACD_EMA_INTERVAL);
                    macd = macdLine.subtract(macdSignalLine);
                    if (currencyPair.toString().equals("GRC<=>BTC") ||
                            currencyPair.toString().equals("EMC2<=>BTC") ||
                            currencyPair.toString().equals("MYR<=>BTC") ||
                            currencyPair.toString().equals("XCP<=>BTC"))
                    {
                        logger.info(currencyPair + " " + priceFormat.format(price) + " " + macdFormat.format(macd));
                        logger.info("***");
                    }
                }
            }
            return true;
        }
        return false;
    }


/*            private void initTrade()
            {
                try
                {
                   
                    long startTime = timeUtils.getCurrentGMTTimeMicros() - MACD_EMA_INTERVAL_MICROS;
                    long timeFrame = MACD_EMA_INTERVAL_MICROS + EMA_LONG_INTERVAL_MICROS;
                    Trade [] trades = provider.getTrades(_tradeSite, _tradedCurrencyPair, timeFrame);
                    System.out.println(trades.length);
                    System.out.println((startTime - timeFrame) + "..." + startTime);
                    logger.info("filling MACD cache");
                    for (int i = MACD_EMA_INTERVALS_NUM - 1; i >= 0; i--)
                    {
                        System.out.println(i);
                        shortEma = analyzer.ema(trades, startTime - EMA_SHORT_INTERVAL_MICROS, startTime, MACD_EMA_TIME_PERIOD);
                        longEma = analyzer.ema(trades, startTime - EMA_LONG_INTERVAL_MICROS, startTime, MACD_EMA_TIME_PERIOD);
                        updateMacdSignals(shortEma, longEma, startTime);
                        startTime += MACD_EMA_TIME_PERIOD;
                    }
                    logger.info("MACD cache filled");
                    lastMacd = macd;

                    depth = provider.getDepth(_tradeSite, _tradedCurrencyPair); 
                    lastPrice = depth.getSell(0).getPrice();
                    initialSellPrice = lastPrice.multiply(BigDecimal.ONE.add(fee));
                    targetBuyPrice = lastPrice.multiply(sellFactor, MathContext.DECIMAL128);
                    stopLossPrice = lastPrice.multiply(stopLossFactor, MathContext.DECIMAL128);
                    BigDecimal currencyValue = getFunds(currency);                                                                                                            
                    BigDecimal payCurrencyValue = getFunds(payCurrency);                                                                                                      
                    initialAssets = initialSellPrice.multiply(currencyValue).add(payCurrencyValue);                                 
                    initialAssetsString = initialAssets.setScale(8, RoundingMode.CEILING).toPlainString();
                    cycleNum = 1;
                    //shortEmaAbove = shortEma.compareTo(longEma) > 0;
                    logger.info("           fee = " + fee);
                    logger.info("   sell factor = " + sellFactor);
                    logger.info("initial assets = " + initialAssetsString);
                }
                catch (Exception e)
                {
                    logger.error(e);
                    System.exit(-1);
                }
            }*/

            /*private void updateMacdSignals(Price shortEma, Price longEma, final long timestamp)
            {
                macdLine = shortEma.subtract(longEma);
                SimpleTrade t = new SimpleTrade(macdLine, timestamp);
                macdCache.add(t);
                if (macdCache.size() > MACD_EMA_INTERVALS_NUM)
                {
                    macdCache.remove(0);
                }
                Trade[] cache = macdCache.toArray(new Trade[0]);
                macdSignalLine = analyzer.ema(cache, MACD_EMA_INTERVAL);
                macd = macdLine.subtract(macdSignalLine);
            }*/

            private static void reportCycleSummary()
            {
                /*String macdSymbol;
                if (order != null)
                {
                    logger.info(String.format("current deal     | %s", order));
                    macdSymbol = "Macd";
                }
                else
                {
                    logger.info("current deal     |");
                    macdSymbol = "macd";
                }
                if (lastDeal != null)
                {
                    logger.info(String.format("last deal        | %s", lastDeal));
                    logger.info(String.format("     +-status    | %s", lastDeal.getStatus()));
                    logger.info(String.format("     +-timestamp | %s", new Date(lastDeal.getTimestamp() / 1000)));
                }
                else
                {
                    logger.info("last deal        |");
                }
                //String priceTrend = macd.signum() > 0 ? "+" : "-";
                String priceTrend = isTrendUp() ? "+" : "-";
                String macdTrend = deltaMacd.signum() > 0 ? "+" : "-";
                BigDecimal uptimeDays = new BigDecimal(cycleNum * UPDATE_INTERVAL / 86400.0);
                BigDecimal currencyValue = getFunds(currency);
                BigDecimal payCurrencyValue = getFunds(payCurrency);
                BigDecimal buyPriceLessFee = buyPrice.multiply(BigDecimal.ONE.subtract(fee));
                BigDecimal currentAssets = buyPriceLessFee.multiply(currencyValue).add(payCurrencyValue);
                BigDecimal absProfit = currentAssets.subtract(initialAssets);
                System.out.println("div3");
                BigDecimal profit = currentAssets.divide(initialAssets, MathContext.DECIMAL128);
                double profitPercent = (profit.doubleValue() - 1) * 100;
                System.out.println("div4");
                double profitPerDay = Math.pow(profit.doubleValue(), BigDecimal.ONE.divide(uptimeDays, MathContext.DECIMAL128).doubleValue());
                double profitPerMonth = Math.pow(profitPerDay, 30);

                // reference profit (refProfit) is a virtual profit of sole investing in currency, without trading
                // it is here for one to be able to compare bot work versus just leave currency intact
                System.out.println("div5");
                BigDecimal refProfit = buyPriceLessFee.divide(initialSellPrice, MathContext.DECIMAL128);
                double refProfitPercent = (refProfit.doubleValue() - 1) * 100;
                System.out.println("div6");
                double refProfitPerDay = Math.pow(refProfit.doubleValue(), BigDecimal.ONE.divide(uptimeDays, MathContext.DECIMAL128).doubleValue());
                double refProfitPerMonth = Math.pow(refProfitPerDay, 30);

                logger.info(String.format("days uptime      |                   %12s                 |", uptimeDays.setScale(3, RoundingMode.CEILING)));
                logger.info(String.format("initial ( %4s ) |                   %12s                 |", payCurrency, initialAssetsString));
                logger.info(String.format("current ( %4s ) |                   %12s                 |", payCurrency, currentAssets.setScale(8, RoundingMode.CEILING)));
                logger.info(String.format("profit ( %4s )  |                   %12s                 |", payCurrency, absProfit.setScale(8, RoundingMode.CEILING)));
                logger.info(String.format("profit (%%)       |                     %+10.1f     %+10.1f* |", profitPercent, refProfitPercent));
                logger.info(String.format("        +-day    |                     %+10.1f     %+10.1f* |", (profitPerDay - 1) * 100, (refProfitPerDay - 1) * 100));
                logger.info(String.format("        +-month  |                     %+10.1f     %+10.1f* |", (profitPerMonth - 1) * 100, (refProfitPerMonth - 1) * 100));

                logger.info(String.format("%4s             |               %16s                 |", currency, currencyValue.setScale(8, RoundingMode.CEILING)));
                logger.info(String.format("%4s             |               %16s                 |", payCurrency, payCurrencyValue.setScale(8, RoundingMode.CEILING)));
                if (targetBuyPrice != null)
                {
                    logger.info(String.format("buy              | %14s  %14s  %14s |",
                                stopLossPrice.setScale(8, RoundingMode.CEILING).toPlainString(),
                                buyPrice.setScale(8, RoundingMode.CEILING).toPlainString(),
                                targetBuyPrice.setScale(8, RoundingMode.CEILING).toPlainString()));
                }
                else
                {
                    logger.info(String.format("buy              |                 %14s                 |", buyPrice.setScale(8, RoundingMode.CEILING).toPlainString()));
                }
                logger.info(String.format("sell             |                 %14s                 |", sellPrice.setScale(8, RoundingMode.CEILING).toPlainString()));
                logger.info(String.format("ema-%3s          |                 %14f                 |", EMA_SHORT_INTERVAL, shortEma));
                logger.info(String.format("ema-%3s          |                 %14f                 |", EMA_LONG_INTERVAL, longEma));
                logger.info(String.format("macd-line        |                 %14f                 |", macdLine));
                logger.info(String.format("macd-signal      |                 %14f                 |", macdSignalLine));
                logger.info(String.format("%s             |  [%7f]   %10s     [ %s ]       |", macdSymbol, relMacd,
                            macd.setScale(12, RoundingMode.CEILING).toPlainString(), priceTrend));
                logger.info(String.format("  +-prev         |                 %10s                |",
                            lastMacd.setScale(12, RoundingMode.CEILING).toPlainString()));
                logger.info(String.format("  +-delta        |                 %10s     [ %s ]       |",
                            deltaMacd.setScale(12, RoundingMode.CEILING).toPlainString(), macdTrend));
                logger.info(              "-----------------+------------------------------------------------+");
                lastMacd = macd;*/
            }

    private static void sleepUntilNextCycle(long t1)
    {
        long t2 = System.currentTimeMillis();
        long sleepTime = (UPDATE_INTERVAL * 1000 - (t2 - t1)); 
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
        tradeArchives = new HashMap<String, TradeArchive>();
        macdArchives = new HashMap<String, TradeArchive>();
        macdCache = new ArrayList<Trade>();  
        analyzer = ChartAnalyzer.getInstance(); 
        provider = ChartProvider.getInstance();
        timeUtils = TimeUtils.getInstance();

        //DecimalFormat amountFormat = new DecimalFormat("#########.########", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        priceFormat = new DecimalFormat("###0.00000000", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        macdFormat = new DecimalFormat("+##0.00000000000;-##0.00000000000", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        //DecimalFormat relMacdFormat = new DecimalFormat("+###.###;-###.###", DecimalFormatSymbols.getInstance(Locale.ENGLISH));        
       
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
                while (true) 
                { 
                    long t1 = System.currentTimeMillis();
                    try
                    {
                        updateTradeArchives();
                        reportCycleSummary();
                    }
                    catch (Exception e)
                    {
                        logger.error(e);
                    }
                    finally
                    {
                        sleepUntilNextCycle(t1);
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
