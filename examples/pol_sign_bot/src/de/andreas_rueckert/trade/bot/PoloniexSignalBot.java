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

import de.andreas_rueckert.trade.site.poloniex.client.PoloniexClient;

import org.apache.log4j.Logger;
import org.apache.log4j.Level;

import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

import de.andreas_rueckert.persistence.PersistentProperty;
import de.andreas_rueckert.persistence.PersistentPropertyList;

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
    private final static int EMA_SHORT_INTERVALS_NUM = 12 * 3;
    private final static int EMA_LONG_INTERVALS_NUM = 26 * 3;
    private final static int MACD_EMA_INTERVALS_NUM = 9 * 3;
    
    private final static long MACD_EMA_TIME_PERIOD = 60L * 1000000L; // one minute - must correspond to next line!
    private final static long MACD_EMA_INTERVAL_MICROS = MACD_EMA_INTERVALS_NUM * MACD_EMA_TIME_PERIOD;
    // Magic below! add 1 (one) extra period to avoid edge effects while calculating MACD
    // i.e. to calculate 9m-EMA of MACD we take 10m-interval for 9 MACD values to be guaranteedly included in the calculation
    private final static String MACD_EMA_INTERVAL = Integer.toString(MACD_EMA_INTERVALS_NUM + 1) + "m"; // here and below 'm' means minute
    
    private final static String EMA_SHORT_INTERVAL = EMA_SHORT_INTERVALS_NUM + "m";
    private final static long EMA_SHORT_INTERVAL_MICROS = MACD_EMA_TIME_PERIOD * EMA_SHORT_INTERVALS_NUM;
    private final static String EMA_LONG_INTERVAL = EMA_LONG_INTERVALS_NUM + "m";
    private final static long EMA_LONG_INTERVAL_MICROS = MACD_EMA_TIME_PERIOD * EMA_LONG_INTERVALS_NUM;

    private static final String DOMAIN = "www.poloniex.com";
    private static final String API_URL_INFO = "https://" + DOMAIN + "/public?command=returnTicker";

    private final BigDecimal THOUSAND = new BigDecimal("1000");

    // Instance variables

    /**
     * The used trade site.
     */
    private TradeSite _tradeSite;

    /**
     * The ticker loop.
     */
    private Thread _updateThread;
    private HashMap<CurrencyPair, TradeArchive> tradeArchives;
    private HashMap<CurrencyPair, TradeArchive> macdArchives;
    private ArrayList<Trade> macdCache;
    private Price shortEma;
    private JSONObject macdStorage;
    private Price longEma;
    private BigDecimal macd;
    private Price macdLine;
    private BigDecimal macdSignalLine;
    private BigDecimal lastMacd;
    private BigDecimal deltaMacd;
    private BigDecimal relMacd;
    private Price buyPrice;
    private Price sellPrice;
    private Order order;
    private Order lastDeal;
    private ChartAnalyzer analyzer;
    private ChartProvider provider;
    private TimeUtils timeUtils;
    private Depth depth;

    public PoloniexSignalBot()
    {
        _tradeSite = new PoloniexClient();
        _updateThread = null;
        tradeArchives = new HashMap<CurrencyPair, TradeArchive>();
        macdCache = new ArrayList<Trade>();  
        analyzer = ChartAnalyzer.getInstance(); 
        provider = ChartProvider.getInstance();
        timeUtils = TimeUtils.getInstance();
    }

    // Methods
    
    public CurrencyPairImpl makeCurrencyPair(String pair)
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

    public boolean updateTradeArchives() 
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

                   TradeArchive archive = tradeArchives.get(currencyPair);
                    if (archive == null)
                    {
                        archive = new TradeArchive(EMA_LONG_INTERVALS_NUM + MACD_EMA_INTERVALS_NUM + 1);
                        tradeArchives.put(currencyPair, archive);
                    }
                    archive.add(new SimpleTrade(price, now));
                    Trade trades[] = archive.toArray(new Trade[0]);
                    shortEma = analyzer.ema(trades, startTime - EMA_SHORT_INTERVAL_MICROS, startTime, MACD_EMA_TIME_PERIOD);
                    longEma = analyzer.ema(trades, startTime - EMA_LONG_INTERVAL_MICROS, startTime, MACD_EMA_TIME_PERIOD);
                    macdLine = shortEma.subtract(longEma);
                    SimpleTrade t = new SimpleTrade(macdLine, now);
                    TradeArchive macdCache = macdArchives.get(currencyPair);
                    if (macdCache == null)
                    {
                        macdCache = new TradeArchive(MACD_EMA_INTERVALS_NUM);
                        macdArchives.put(currencyPair, macdCache);
                    }
                    macdCache.add(t);
                    Trade[] cache = macdCache.toArray(new Trade[0]);
                    macdSignalLine = analyzer.ema(cache, MACD_EMA_INTERVAL);
                    macd = macdLine.subtract(macdSignalLine);
                    relMacd = macd.divide(price, MathContext.DECIMAL128).multiply(THOUSAND);
                    deltaMacd = macd.subtract(lastMacd);
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

            private void reportCycleSummary()
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

    private void sleepUntilNextCycle(long t1)
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

    public void main(String args[])
    {
        final Logger logger = LogUtils.getInstance().getLogger();
        logger.setLevel(Level.INFO);
        logger.info("PoloniexSignalBot started");
       
        // Create a ticker thread.
        _updateThread = new Thread() 
        {

            /**
            * The main bot thread.
            */
            @Override public void run() 
            {
                while (_updateThread == this) 
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
 	    };
	    _updateThread.start();  // Start the update thread.
    }
    
    class SimpleTrade implements Trade
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


    class TradeArchive extends ArrayList<Trade>
    {
        private int maxCapacity;

        public TradeArchive(int capacity)
        {
            maxCapacity = capacity;
        }

        public boolean add(Trade e)
        {
            if (size == maxCapacity)
            {
                remove(0);
            }
            return add(e);
        }

        public void add(int index, Trade e)
        {
            if (size() == maxCapacity)
            {
                remove(0);
            }
            add(index, e);
        }

    }

}
