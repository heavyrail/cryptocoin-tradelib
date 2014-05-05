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

import de.andreas_rueckert.persistence.PersistentProperty;
import de.andreas_rueckert.persistence.PersistentPropertyList;
import de.andreas_rueckert.trade.*;
import de.andreas_rueckert.trade.account.TradeSiteAccount;
import de.andreas_rueckert.trade.bot.ui.MaBotUI;
import de.andreas_rueckert.trade.chart.ChartProvider;
import de.andreas_rueckert.trade.chart.ChartAnalyzer;
import de.andreas_rueckert.trade.order.*;
import de.andreas_rueckert.trade.site.TradeSite;
import de.andreas_rueckert.trade.site.TradeSiteUserAccount;
import de.andreas_rueckert.trade.site.poloniex.client.PoloniexClient;
import de.andreas_rueckert.trade.site.poloniex.client.PoloniexCurrencyPairImpl;
import de.andreas_rueckert.util.*;

import java.io.IOException;
import java.io.File;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;

// we do not use wildcard * on java.util imports for java.util.Currency to not conflict with Andreas Rueckert's Currency class
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.Scanner;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

/**
 * This is a simple bot to demonstrate the usage of the cryptocoin tradelib.
 */
public class MaBot implements TradeBot {

    enum State {TARGETING, HUNGRY, TRADING, DUMPING};

    // Static variables

    /**
     * The minimal profit
     */
    //private final static BigDecimal MIN_PROFIT = new BigDecimal("0.07568430"); // sf=1.08
    private final static BigDecimal MIN_PROFIT = new BigDecimal("0.08564436"); // sf=1.09
    //private final static BigDecimal MIN_PROFIT = new BigDecimal("0.09560440"); // sf=1.10

    /**
     * The maximum loss
     */
    private final static BigDecimal LOSS_TO_STOP = new BigDecimal("0.3");
    private final static BigDecimal STOP_LOSS_FACTOR = BigDecimal.ONE.subtract(LOSS_TO_STOP);
    
    private final static BigDecimal REL_MACD_THRESHOLD = new BigDecimal("5");

    /**
     * The minimal trade volume.
     */
    private final static BigDecimal MIN_TRADE_AMOUNT = new Amount("0.0001");

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

    private final BigDecimal TWO = new BigDecimal("2");
    private final BigDecimal THOUSAND = new BigDecimal("1000");

    private final int MAX_HOT_BTC_PAIRS = 5;
    private final int MAX_HOT_LTC_PAIRS = 3;

    private final static String TAKEN_PAIRS_FILE = "taken_pairs.txt";

    // Instance variables
    
    State state;

    Logger logger;

    String configFilename;

    /**
     * The user inface of this bot.
     */
    MaBotUI _botUI = null;

    /**
     * The traded currency pair.
     */
    CurrencyPair _tradedCurrencyPair = null;

    /**
     * The used trade site.
     */
    private TradeSite _tradeSite = null;

    /**
     * The ticker loop.
     */
    private Thread _updateThread = null;

    private TradeSiteUserAccount _tradeSiteUserAccount = null;

    private Currency currency;

    private Currency payCurrency;

    private CryptoCoinOrderBook orderBook;

    private Price lastPrice;

    private BigDecimal targetBuyPrice;

    private BigDecimal stopLossPrice;

    private BigDecimal fee;

    private BigDecimal initialAssets;

    private String proxy;

    private boolean proxyEnabled;

    private BigDecimal initialSellPrice;

    private int cycleNum;

    private ChartAnalyzer analyzer;
    
    private ChartProvider provider;
    
    private TimeUtils timeUtils;

    // Constructors

    /**
     * Create a new bot instance.
     */
    public MaBot(String configFilename) 
    {
        this.configFilename = configFilename;
        StringBuilder configLine = new StringBuilder();
        try
        {
            Scanner s = new Scanner(new File(configFilename));
            if (s.hasNextLine())
            {
                configLine.append(s.nextLine());
            }
        }
        catch (IOException e)
        {
            System.exit(-1);
        }
        _tradeSiteUserAccount = TradeSiteUserAccount.fromPropertyValue(configLine.toString());
        _tradeSite = _tradeSiteUserAccount.getTradeSite();    
        PersistentPropertyList settings = new PersistentPropertyList();
        settings.add(new PersistentProperty("Key", null, _tradeSiteUserAccount.getAPIkey(), 0));
        settings.add(new PersistentProperty("Secret", null, _tradeSiteUserAccount.getSecret(), 0));
        proxy = _tradeSiteUserAccount.getProxy();
        proxyEnabled = proxy != null && proxy.length() > 0;
        _tradeSite.setSettings(settings);
        orderBook = (CryptoCoinOrderBook) CryptoCoinOrderBook.getInstance();
        provider = ChartProvider.getInstance();
        logger = LogUtils.getInstance().getLogger();
        logger.setLevel(Level.INFO);
        setState(State.TARGETING);
    }

    // Methods

    private void setState(State newState)
    {
        state = newState;
        logger.info("state is set to " + state.name());
    }

   /**
     * Get the funds for a given currency.
     *
     * @param currency The currency to use.
     *
     * @return The balance for this currency (or -1, if no account with this currency was found).
     */
    public BigDecimal getFunds( Currency currency) {

	Collection<TradeSiteAccount> currentFunds = _tradeSite.getAccounts(_tradeSiteUserAccount);  // fetch the accounts from the trade site.

        if( currentFunds == null) {
            LogUtils.getInstance().getLogger().error( "MaBot cannot fetch accounts from trade site.");
        } else {
            for( TradeSiteAccount account : currentFunds) {    // Loop over the accounts.
                if( currency.equals( account.getCurrency())) {  // If this accounts has the requested currency.
                    logger.info("we have a balance of " + account.getBalance());
                    return account.getBalance();                // Return it's balance.
                }
            }
        }

        return null;  // Cannot get any balance. 
    }

    /**
     * Get the name of this bot.
     *
     * @return The name of this bot.
     */
    public String getName() 
    {
        //return configFilename;
        return ManagementFactory.getRuntimeMXBean().getName();
    }

    /**
     * Get a property value from this bot.
     *
     /* @param propertyName The name of the property.
     *
     * @return The value of this property as a String object, or null if it's an unknown property.
     */
    public String getTradeBotProperty( String propertyName) {

        return null;  // Did not find a property with this name.
    }
    
    /**
     * Get the UI for this bot.
     *
     * @return The UI for this bot.
     */
    public MaBotUI getUI() {
        if( _botUI == null) {                    // If there is no UI yet,
            _botUI = new MaBotUI( this);         // create one. This is optional, since the bot
        }                                       // might run in daemon mode.
        return _botUI;
    }

    /**
     * Get the version string of this bot.
     *
     * @return The version string of this bot.
     */
    public String getVersionString() {

        // Get the version of this bot as a string.
        return "0.1.0 ( Janker )";
    }

    /**
     * Check, if the bot is currently stopped.
     *
     * @return true, if the bot is currently stopped. False otherwise.
     */
    public boolean isStopped() {
        return _updateThread == null;
    }

    /**
     * Set some property value in the bot.
     *
     * @param propertyName The name of then property.
     * @param propertyValue The value of the property.
     */
    public void setTradeBotProperty( String propertyName, String propertyValue) {
    }

    /**
     * Start the bot.
     */
    public void start() 
    {
        logger.info("MABot started");
       
        // Create a ticker thread.
        _updateThread = new Thread() 
        {
            ArrayList<Trade> macdCache;
            BigDecimal macd;
            Price macdLine;
            BigDecimal macdSignalLine;
            BigDecimal lastRelMacd;
            BigDecimal deltaRelMacd;
            BigDecimal relMacd;
            Price buyPrice;
            Price sellPrice;
            Order order;
            Order lastDeal;
            Depth depth;
            BigDecimal sellFactor;
            BigDecimal takeProfitFactor;
            String pendingOrderId;

            /**
            * The main bot thread.
            */
            @Override public void run() 
            {
                while( _updateThread == this) 
                { 
                    long t1 = System.currentTimeMillis();
                    try
                    {
                        switch (state)
                        {
                            case TARGETING:
                                if (initTrade())
                                {
                                    setState(MaBot.State.HUNGRY);
                                }
                                break;
                            case HUNGRY:
                                setState(MaBot.State.TRADING);
                                break;
                            case TRADING:
                                tradeCurrencies();
                                break;
                            case DUMPING:
                                setState(MaBot.State.TARGETING);
                                break;
                        }
                    }
                    catch (Exception e)
                    {
                        logger.error(e);
                    }
                    finally
                    {
                        if (state == MaBot.State.TRADING)
                        {
                            cycleNum++;
                            sleepUntilNextCycle(t1);
                        }
                    } 
		        }
		    }

            private boolean chooseCurrency()
            {
                if (!proxyEnabled)
                {
                    logger.error("at the moment I can't work without proxy");
                    System.exit(-1);
                }
                else
                {
                    RandomAccessFile pairsFile = null;
                    FileLock fileLock = null;
                    try
                    {
                        pairsFile = new RandomAccessFile(TAKEN_PAIRS_FILE, "rw");
                        FileChannel fileChannel = pairsFile.getChannel();
                        fileLock = fileChannel.tryLock();
                        if (fileLock != null)
                        {
                            logger.info("choose currency lock acquired");
                            String requestResult = HttpUtils.httpGet(proxy + "/macd.html");
                            JSONObject signals = JSONObject.fromObject(requestResult);
                            _tradedCurrencyPair = chooseRisingCurrency(signals, "BTC", MAX_HOT_BTC_PAIRS, pairsFile);
                            if (_tradedCurrencyPair == null)
                            {
                                _tradedCurrencyPair = chooseRisingCurrency(signals, "LTC", MAX_HOT_LTC_PAIRS, pairsFile);
                            }
                            if (_tradedCurrencyPair != null)
                            {
                                payCurrency = _tradedCurrencyPair.getPaymentCurrency();                
                                currency = _tradedCurrencyPair.getCurrency();
                                return true;
                            }
                            logger.info("no pair selected");
                        }
                    }
                    catch (IOException e)
                    {
                        logger.error("cannot acquire currency lock");
                    }
                    finally
                    {
                        if (fileLock != null)
                        {
                            try
                            {
                                fileLock.release();
                                logger.info("choose currency lock released");
                            }
                            catch (IOException e)
                            {
                                logger.error("cannot release currency lock");
                            }
                        }
                    }             
                }
                return false;
            }

            private CurrencyPair chooseRisingCurrency(JSONObject signals, String paymentCurrencyString, int maxPairs, RandomAccessFile pairsFile)
            {
                CurrencyPair result = null;
                String requestResult = HttpUtils.httpGet(proxy + "/hot_" + paymentCurrencyString + ".html");
                JSONArray pairs = JSONArray.fromObject(requestResult);
                for (int i = 0; i < maxPairs; i++)
                {
                    JSONObject hotPair = pairs.getJSONObject(i);
                    Iterator<String> keys = hotPair.keys();
                    keys.next();
                    String currencyString = keys.next();
                    JSONArray hotSignals = signals.getJSONArray(paymentCurrencyString + "_" + currencyString);
                    BigDecimal hotRelMacd = new BigDecimal(hotSignals.getString(2)); 
                    logger.info(paymentCurrencyString + "_" + currencyString + " " + hotRelMacd);
                    JSONObject takenPairs = readTakenPairs(pairsFile);
                    if (takenPairs != null)
                    {
                        boolean pairAvailable = !hasCurrencyPairTaken(takenPairs, currencyString, paymentCurrencyString);
                        if (pairAvailable)
                        {
                            logger.info("pair is available, let's take it");
                        }
                        else
                        {
                            logger.info("pair is already taken, skip it");
                        }
                        if (hotRelMacd.compareTo(REL_MACD_THRESHOLD) >= 0 && pairAvailable)
                        {
                            if (takePair(takenPairs, pairsFile, currencyString, paymentCurrencyString))
                            {
                                logger.info(currencyString + " " + hotRelMacd);
                                result = PoloniexCurrencyPairImpl.findByString(currencyString + "<=>" + paymentCurrencyString);
                            }
                            else
                            {
                                logger.error("cannot write taken pair to file");
                            }
                            break;
                        }
                    }
                    else
                    {
                        logger.error("cannot parse taken pairs file");
                    }
                }
                return result;
            }

            private boolean takePair(JSONObject takenPairs, RandomAccessFile pairsFile, String currencyString, String paymentCurrencyString)
            {
                takenPairs.put(paymentCurrencyString + "_" + currencyString, getName());
                try 
                {
                    pairsFile.setLength(0);
                    pairsFile.write(takenPairs.toString().getBytes());
                    //pairsFile.close();
                    return true;
                }
                catch (IOException e)
                {
                    return false;
                }
            }

            private JSONObject readTakenPairs(RandomAccessFile pairsFile)
            {
                try
                {
                    long len = pairsFile.length();
                    if (len > 0)
                    {
                        byte buf[] = new byte[(int) len];
                        pairsFile.seek(0);
                        pairsFile.readFully(buf);
                        return JSONObject.fromObject(new String(buf));
                    }
                    else
                    {
                        return new JSONObject();
                    }
                }
                catch (IOException e)
                {
                    return null;
                }
            }

            private boolean hasCurrencyPairTaken(JSONObject takenPairs, String currencyString, String paymentCurrencyString)
            {
                String pair = paymentCurrencyString + "_" + currencyString;
                return takenPairs.has(pair);
            }

            private void calculateCoeffs()
            {
                fee = _tradeSite.getFeeForTrade();
                if (_tradeSite instanceof PoloniexClient)
                {
                    ((PoloniexClient) _tradeSite).setCurrencyPair(_tradedCurrencyPair);
                }
                else
                {
                    logger.error("only poloniex client supported at the moment");
                    System.exit(-1);
                }
                BigDecimal doubleFee = fee.add(fee);
                BigDecimal feeSquared = fee.multiply(fee, MathContext.DECIMAL128);
                BigDecimal priceCoeff = BigDecimal.ONE.subtract(doubleFee).add(feeSquared);
                BigDecimal profitCoeff = BigDecimal.ONE.add(MIN_PROFIT);
                sellFactor = profitCoeff.divide(priceCoeff, MathContext.DECIMAL128);
            }

            private void displayCoeffs()
            {
                BigDecimal currencyValue = getFunds(currency);                                                                                                            
                BigDecimal payCurrencyValue = getFunds(payCurrency);                                                                                                      
                initialAssets = initialSellPrice.multiply(currencyValue).add(payCurrencyValue);                                 
                logger.info("           fee = " + fee);
                logger.info("   sell factor = " + sellFactor);
                logger.info("initial assets = " + initialAssets);
            }

            private void calculatePriceThresholds()
            {
                depth = provider.getDepth(_tradeSite, _tradedCurrencyPair); 
                lastPrice = depth.getSell(0).getPrice();
                initialSellPrice = lastPrice.multiply(BigDecimal.ONE.add(fee));
                targetBuyPrice = lastPrice.multiply(sellFactor, MathContext.DECIMAL128);
                stopLossPrice = lastPrice.multiply(STOP_LOSS_FACTOR, MathContext.DECIMAL128);
            }

            private boolean initTrade()
            {
                lastDeal = null;
                lastRelMacd = null;
                try
                {
                    if (chooseCurrency())
                    {
                        calculateCoeffs();
                        if (!proxyEnabled)
                        {
                            macdCache = new ArrayList<Trade>();  
                            analyzer = ChartAnalyzer.getInstance(); 
                            timeUtils = TimeUtils.getInstance();
                            updateSignalsDirectly();
                        }
                        else
                        {
                            getSignalsFromProxy();
                        }
                        calculatePriceThresholds();
                        displayCoeffs();
                        cycleNum = 1;
                        return true;
                    }
                }
                catch (Exception e)
                {
                    logger.error(e);
                    System.exit(-1);
                }
                return false;
            }

            private void tradeCurrencies()
            {
                checkPendingOrder();
                calculateSignals();
                doTrade();
                reportCycleSummary();
                lastRelMacd = relMacd;
            }

            private void checkPendingOrder()
            {
                if (pendingOrderId != null) 
                {
                    Order pendingOrder = orderBook.getOrder(pendingOrderId);
                    if (!orderBook.isCompleted(pendingOrderId))
                    {
                        logger.info("cancelling order on hold");
                        if (orderBook.cancelOrder(pendingOrder))
                        {
                            pendingOrderId = null;
                        }
                    }
                    else
                    {
                        pendingOrderId = null;
                        lastDeal = pendingOrder;
                        lastPrice = pendingOrder.getPrice();
                        OrderStatus status = pendingOrder.getStatus();
                        if (pendingOrder.getOrderType() == OrderType.BUY && (
                                 status == OrderStatus.FILLED || status == OrderStatus.PARTIALLY_FILLED))
                        {
                            logger.info("adjusting stop loss and target buy prices");
                            stopLossPrice = lastPrice.multiply(STOP_LOSS_FACTOR);
                            targetBuyPrice = lastPrice.multiply(sellFactor);
                        }
                        else
                        {
                            logger.info("stop loss and target buy prices remain intact");
                            // TODO
                        }
                    }
                }                        
            }
            
            private void calculateSignals()
            {
   	            depth = provider.getDepth(_tradeSite, _tradedCurrencyPair);
                buyPrice = depth.getBuy(0).getPrice();
                sellPrice = depth.getSell(0).getPrice();
                if (!proxyEnabled)
                {
                    BigDecimal meanPrice = buyPrice.add(sellPrice).divide(TWO, MathContext.DECIMAL128);
                    Price shortEma = analyzer.getEMA(_tradeSite, _tradedCurrencyPair, EMA_SHORT_INTERVAL);
                    Price longEma = analyzer.getEMA(_tradeSite, _tradedCurrencyPair, EMA_LONG_INTERVAL);
                    updateMacdSignalsDirectly(shortEma, longEma, timeUtils.getCurrentGMTTimeMicros());                
                    relMacd = macd.divide(meanPrice, MathContext.DECIMAL128).multiply(THOUSAND);
                    deltaRelMacd = relMacd.subtract(lastRelMacd);
                }
                else
                {
                    getSignalsFromProxy();
                }

                /* should a short EMA rise too high above target buy price, move stop loss up too */
                /*if (shortEma.compareTo(targetBuyPrice) > 0)
                {
                    stopLossPrice = sellPrice.multiply(STOP_LOSS_FACTOR, MathContext.DECIMAL128);
                    logger.info("*** Stop Loss Adjusted ***");
                }*/
            }

            private void getSignalsFromProxy()
            {
                String requestResult = HttpUtils.httpGet(proxy + "/macd.html");
                String pair = 
                        _tradedCurrencyPair.getPaymentCurrency().getName().toUpperCase() +
                        "_" +
                        _tradedCurrencyPair.getCurrency().getName().toUpperCase();
                JSONArray ticker = JSONObject.fromObject(requestResult).getJSONArray(pair);

                macd = new Price(ticker.getString(1));
                relMacd = new Price(ticker.getString(2));
                if (deltaRelMacd == null || lastRelMacd == null)
                {
                    deltaRelMacd = new Price("0");
                }
                else
                {
                    deltaRelMacd = relMacd.subtract(lastRelMacd);
                }
            }

            private void doTrade()
            {
                order = null;
                if (isTimeToBuy()) 
                {
 			        order = buyCurrency(depth);
                }
                else if (isStopLoss() || isMinProfit()) 
                {
	                order = sellCurrency(depth); 
                }
                if (order != null && order.getStatus() != OrderStatus.ERROR)
                {
                    pendingOrderId = order.getId();                        
                }                
            }

            private boolean isTrendUp()
            {
                return macd.signum() > 0;
            }

            private boolean isStopLoss()
            {
                if (buyPrice.compareTo(stopLossPrice) < 0)
                {
                    logger.info("*** Stop Loss ***");
                    return true;
                }
                else
                {
                    return false;
                }
            }

            private boolean isMinProfit()
            {
                if (targetBuyPrice != null && buyPrice.compareTo(targetBuyPrice) > 0 && !isTrendUp())
                {
                    logger.info("*** Minimal Profit ***");
                    return true;
                }
                else
                {
                    return false;
                }
            }

            private boolean isTimeToBuy()
            {
                if (isTrendUp())
                {
                    logger.info("*** Time To Buy ***");
                    return true;
                }
                else
                {
                    return false;
                }
            }

            private Order buyCurrency(Depth depth)
            {
                // Check, if there is an opportunity to buy something, and the volume of the
		        // order is higher than the minimum trading volume.

                int sellOrders = depth.getSellSize();
                int i = 0;
                DepthOrder depthOrder = null;
                Amount availableAmount = null;
                Price sellPrice = null;
                BigDecimal payCurrencyAmount = null;
                do
                {
                    depthOrder = depth.getSell(i++);
                    availableAmount = depthOrder.getAmount();
                    sellPrice = depthOrder.getPrice();
                    payCurrencyAmount = availableAmount.multiply(sellPrice); 
                }
                while (i < sellOrders && payCurrencyAmount.compareTo(MIN_TRADE_AMOUNT) < 0);
               
                System.out.println(payCurrencyAmount);

                if (payCurrencyAmount.compareTo(MIN_TRADE_AMOUNT) >= 0)
                {

                    // Now check, if we have any funds to buy something.
                    BigDecimal funds = getFunds(payCurrency); 
              
			        Amount buyAmount = new Amount(funds.divide(sellPrice, MathContext.DECIMAL128));

		            // Compute the actual amount to trade.
				    Amount orderAmount = availableAmount.compareTo(buyAmount) < 0 ? availableAmount : buyAmount;

				    if (orderAmount.multiply(sellPrice).compareTo(MIN_TRADE_AMOUNT) >= 0)
                    {
                        // Create a buy order...
			            String orderId = orderBook.add(OrderFactory.createCryptoCoinTradeOrder(
                                _tradeSite, _tradeSiteUserAccount, OrderType.BUY, sellPrice, _tradedCurrencyPair, orderAmount));
                        return orderBook.getOrder(orderId);
                    }
                    else
                    {
                        logger.info("amount you want to buy is lower than minimum!");
                    }
                }   
                else
                {
                    logger.info("amount market can sell is lower than minimum!");
                }
                return null;
            }

            private Order sellCurrency(Depth depth)
            {
                // Check, if there is an opportunity to sell some funds, and the volume of the order
                // is higher than the minimum trading volume.

                int buyOrders = depth.getBuySize();
                int i = 0;
                DepthOrder depthOrder = null;
                Amount availableAmount = null;
                Price buyPrice = null;
                BigDecimal payCurrencyAmount = null;
                do
                {
                    depthOrder = depth.getBuy(i++);
                    availableAmount = depthOrder.getAmount();
                    buyPrice = depthOrder.getPrice();
                    payCurrencyAmount = availableAmount.multiply(buyPrice); 
                }
                while (i < buyOrders && payCurrencyAmount.compareTo(MIN_TRADE_AMOUNT) < 0);            

                if (payCurrencyAmount.compareTo(MIN_TRADE_AMOUNT) >= 0) 
                {
		            // Now check, if we have any funds to sell.
			        Amount sellAmount = new Amount(getFunds(currency));

                    // Compute the actual amount to trade.
                    Amount orderAmount = availableAmount.compareTo(sellAmount) < 0 ? availableAmount : sellAmount;

                    if (sellAmount.multiply(buyPrice).compareTo(MIN_TRADE_AMOUNT) >= 0) 
                    {
	                    // Create a sell order...
		                String orderId = orderBook.add(OrderFactory.createCryptoCoinTradeOrder(
                                _tradeSite, _tradeSiteUserAccount, OrderType.SELL, buyPrice, _tradedCurrencyPair, orderAmount));
                        return orderBook.getOrder(orderId);
                    }
                    else
                    {
                        logger.info("your funds to sell are lower than minimum!");
                    }
		        }
                else
                {
                    logger.info("funds market can buy price are lower than minimum!");
                }
                return null;
            }

            private void updateSignalsDirectly()
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
                    Price shortEma = analyzer.ema(trades, startTime - EMA_SHORT_INTERVAL_MICROS, startTime, MACD_EMA_TIME_PERIOD);
                    Price longEma = analyzer.ema(trades, startTime - EMA_LONG_INTERVAL_MICROS, startTime, MACD_EMA_TIME_PERIOD);
                    updateMacdSignalsDirectly(shortEma, longEma, startTime);
                    startTime += MACD_EMA_TIME_PERIOD;
                }
                logger.info("MACD cache filled");
                lastRelMacd = relMacd;
            }
 
            private void updateMacdSignalsDirectly(Price shortEma, Price longEma, final long timestamp)
            {
                macdLine = shortEma.subtract(longEma);
                final Price price = macdLine;
                Trade t = new Trade()
                {
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
                };
                macdCache.add(t);
                if (macdCache.size() > MACD_EMA_INTERVALS_NUM)
                {
                    macdCache.remove(0);
                }
                Trade[] cache = macdCache.toArray(new Trade[0]);
                macdSignalLine = analyzer.ema(cache, MACD_EMA_INTERVAL);
                macd = macdLine.subtract(macdSignalLine);
            }

            private void reportCycleSummary()
            {
                String macdSymbol;
                if (order != null)
                {
                    logger.info(String.format("current deal     | %s", order));
                    macdSymbol = "[ Macd x 1000 ]";
                }
                else
                {
                    logger.info("current deal     |");
                    macdSymbol = "[ macd x 1000 ]";
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
                BigDecimal uptimeDays = new BigDecimal(cycleNum * UPDATE_INTERVAL / 86400.0);
                BigDecimal currencyValue = getFunds(currency);
                BigDecimal payCurrencyValue = getFunds(payCurrency);
                BigDecimal buyPriceLessFee = buyPrice.multiply(BigDecimal.ONE.subtract(fee));
                BigDecimal currentAssets = buyPriceLessFee.multiply(currencyValue).add(payCurrencyValue);
                BigDecimal absProfit = currentAssets.subtract(initialAssets);
                BigDecimal profit = currentAssets.divide(initialAssets, MathContext.DECIMAL128);
                double profitPercent = (profit.doubleValue() - 1) * 100;
                double profitPerDay = Math.pow(profit.doubleValue(), BigDecimal.ONE.divide(uptimeDays, MathContext.DECIMAL128).doubleValue());
                double profitPerMonth = Math.pow(profitPerDay, 30);

                // reference profit (refProfit) is a virtual profit of sole investing in currency, without trading
                // it is here for one to be able to compare bot work versus just leave currency intact
                BigDecimal refProfit = buyPriceLessFee.divide(initialSellPrice, MathContext.DECIMAL128);
                double refProfitPercent = (refProfit.doubleValue() - 1) * 100;
                double refProfitPerDay = Math.pow(refProfit.doubleValue(), BigDecimal.ONE.divide(uptimeDays, MathContext.DECIMAL128).doubleValue());
                double refProfitPerMonth = Math.pow(refProfitPerDay, 30);
                
                DecimalFormat amountFormat = new DecimalFormat("########0.00000000", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
                DecimalFormat priceFormat = new DecimalFormat("###0.00000000", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
                DecimalFormat macdFormat = new DecimalFormat("+###.########;-###.########", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
                DecimalFormat relMacdFormat = new DecimalFormat("+###.###;-###.###", DecimalFormatSymbols.getInstance(Locale.ENGLISH));      
                
                logger.info(String.format("days uptime      |                   %12s                 |", uptimeDays.setScale(3, RoundingMode.CEILING)));
                logger.info(String.format("initial ( %5s) |             %18s                 |", payCurrency, amountFormat.format(initialAssets)));
                logger.info(String.format("current ( %5s) |             %18s                 |", payCurrency, amountFormat.format(currentAssets)));
                logger.info(String.format("profit  ( %5s) |             %18s                 |", payCurrency, amountFormat.format(absProfit)));
                logger.info(String.format("profit in %%      |                     %+10.1f     %+10.1f* |", profitPercent, refProfitPercent));
                logger.info(String.format("        +-day    |                     %+10.1f     %+10.1f* |", (profitPerDay - 1) * 100, (refProfitPerDay - 1) * 100));
                logger.info(String.format("        +-month  |                     %+10.1f     %+10.1f* |", (profitPerMonth - 1) * 100, (refProfitPerMonth - 1) * 100));

                logger.info(String.format("%5s            |               %16s                 |", currency, amountFormat.format(currencyValue)));
                logger.info(String.format("%5s            |               %16s                 |", payCurrency, amountFormat.format(payCurrencyValue)));
                if (targetBuyPrice != null)
                {
                    logger.info(String.format("buy              |  %13s   %13s   %13s |",
                                priceFormat.format(stopLossPrice), priceFormat.format(buyPrice), priceFormat.format(targetBuyPrice)));
                }
                else
                {
                    logger.info(String.format("buy              |                  %13s                 |", priceFormat.format(buyPrice)));
                }
                logger.info(String.format("sell             |                  %13s                 |", priceFormat.format(sellPrice)));
                logger.info(String.format("%s  | [%13s ]      %8s                 |", macdSymbol, macdFormat.format(macd), relMacdFormat.format(relMacd)));
                if (lastRelMacd != null)
                {
                    logger.info(String.format("  +-prev (rel.)  |                       %8s                 |", macdFormat.format(lastRelMacd)));
                    logger.info(String.format("  +-delta (rel.) |                       %8s                 |", macdFormat.format(deltaRelMacd)));
                }
                logger.info(              "-----------------+------------------------------------------------+");
            }

            private void sleepUntilNextCycle(long t1)
            {
                logger.info("going to sleep");
                long t2 = System.currentTimeMillis();
                long sleepTime = (UPDATE_INTERVAL * 1000 - (t2 - t1)); 
                if (sleepTime > 0)
                {
			        try 
                    {
                        sleep(sleepTime);  // Wait for the next loop.
                    } 
                    catch( InterruptedException ie) 
                    {
                        System.err.println( "Ticker or depth loop sleep interrupted: " + ie.toString());
                    }
                }
                logger.info("wake up, wake up, little sparrow");
            }
	    };

	    _updateThread.start();  // Start the update thread.
    }
    
    /**
     * Stop the bot.
     */
    public void stop() {
	
        Thread updateThread = _updateThread;  // So we can join the thread later.
        
        _updateThread = null;  // Signal the thread to stop.
        
        try {
            updateThread.join();  // Wait for the thread to end.

        } catch( InterruptedException ie)  {
            System.err.println( "Ticker stop join interrupted: " + ie.toString());
        }
    }
}
