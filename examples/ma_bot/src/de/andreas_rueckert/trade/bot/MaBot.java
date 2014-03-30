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

import de.andreas_rueckert.trade.site.TradeSiteUserAccount;
import de.andreas_rueckert.trade.account.TradeSiteAccount;
import de.andreas_rueckert.trade.Amount;
import de.andreas_rueckert.trade.bot.ui.MaBotUI;
import de.andreas_rueckert.trade.chart.ChartProvider;
import de.andreas_rueckert.trade.chart.ChartAnalyzer;
import de.andreas_rueckert.trade.Currency;
import de.andreas_rueckert.trade.CurrencyPair;
import de.andreas_rueckert.trade.CurrencyPairImpl;
import de.andreas_rueckert.trade.Depth;

import de.andreas_rueckert.trade.Trade;
import de.andreas_rueckert.trade.TradeType;
import de.andreas_rueckert.trade.order.CryptoCoinOrderBook;
import de.andreas_rueckert.trade.order.Order;
import de.andreas_rueckert.trade.order.SiteOrder;

import de.andreas_rueckert.trade.order.OrderFactory;
import de.andreas_rueckert.trade.order.OrderType;
import de.andreas_rueckert.trade.order.OrderStatus;
import de.andreas_rueckert.trade.order.DepthOrder;
import de.andreas_rueckert.trade.Price;
import de.andreas_rueckert.trade.site.TradeSite;
import de.andreas_rueckert.util.LogUtils;
import de.andreas_rueckert.util.ModuleLoader;
import de.andreas_rueckert.util.TimeUtils;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Scanner;
import java.util.LinkedList;
import java.io.IOException;
import java.io.File;

import de.andreas_rueckert.trade.site.btc_e.client.BtcEClient;
import de.andreas_rueckert.trade.site.poloniex.client.PoloniexClient;
import org.apache.log4j.Logger;
import org.apache.log4j.Level;
import de.andreas_rueckert.persistence.PersistentProperty;
import de.andreas_rueckert.persistence.PersistentPropertyList;

/**
 * This is a simple bot to demonstrate the usage of the cryptocoin tradelib.
 */
public class MaBot implements TradeBot {

    // Static variables

    /**
     * The minimal profit
     */
    private final static BigDecimal MIN_PROFIT = new BigDecimal("0.02588412");

    /**
     * The maximum loss
     */
    private final static BigDecimal LOSS_TO_STOP = new BigDecimal("0.3");

    /**
     * The minimal trade volume.
     */
    private final static BigDecimal MIN_TRADE_AMOUNT = new Amount("0.01");

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

    // Instance variables

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

    private String initialAssetsString;

    private BigDecimal initialSellPrice;

    private int cycleNum;

    // Constructors

    /**
     * Create a new bot instance.
     */
    public MaBot() {

	// Set trade site and currency pair to trade.
    StringBuilder configLine = new StringBuilder();
    try
    {
        Scanner s = new Scanner(new File("mabot.cfg"));
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
    _tradeSite.setSettings(settings);
	_tradedCurrencyPair = CurrencyPairImpl.findByString("DRK<=>BTC");
    payCurrency = _tradedCurrencyPair.getPaymentCurrency();                
    currency = _tradedCurrencyPair.getCurrency();
    orderBook = (CryptoCoinOrderBook) CryptoCoinOrderBook.getInstance();
    }
    

    // Methods

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
    public String getName() {
        return "MovingAverage";
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

        final Logger logger = LogUtils.getInstance().getLogger();
        logger.setLevel(Level.INFO);
        logger.info("MABot started");
       
        // Create a ticker thread.
        _updateThread = new Thread() 
        {

            Price shortEma;
            Price longEma;
            ArrayList<Trade> macdCache;
            BigDecimal macd;
            Price macdLine;
            BigDecimal macdSignalLine;
            BigDecimal lastMacd;
            BigDecimal deltaMacd;
            BigDecimal relMacd;
            Price buyPrice;
            Price sellPrice;
            Order order;
            Order lastDeal;
            ChartAnalyzer analyzer;
            ChartProvider provider;
            TimeUtils timeUtils;
            Depth depth;
            BigDecimal sellFactor;
            BigDecimal stopLossFactor;
            BigDecimal takeProfitFactor;
            //BigDecimal oldCurrencyAmount;
            //BigDecimal oldPaymentCurrencyAmount;
            String pendingOrderId;

            /**
            * The main bot thread.
            */
            @Override public void run() 
            {
                initTrade();
                while( _updateThread == this) 
                { 
                    long t1 = System.currentTimeMillis();
                    try
                    {
                        checkPendingOrder();
                        calculateSignals();
                        doTrade();
                        reportCycleSummary();
                    }
                    catch (Exception e)
                    {
                        logger.error(e);
                    }
                    finally
                    {
                        cycleNum++;
                        sleepUntilNextCycle(t1);
                    } 
		        }
		    }

            private void initTrade()
            {
                macdCache = new ArrayList<Trade>();  
                stopLossFactor = BigDecimal.ONE.subtract(LOSS_TO_STOP);
                lastDeal = null;
                try
                {
                    if (_tradeSite instanceof BtcEClient)
                    {
                        fee = ((BtcEClient) _tradeSite).getFeeForCurrencyPairTrade(_tradedCurrencyPair);
                    }
                    else
                    {
                        fee = _tradeSite.getFeeForTrade();
                    }
                    if (_tradeSite instanceof PoloniexClient)
                    {
                        ((PoloniexClient) _tradeSite).setCurrencyPair(_tradedCurrencyPair);
                    }
                    BigDecimal doubleFee = fee.add(fee);
                    BigDecimal feeSquared = fee.multiply(fee, MathContext.DECIMAL128);
                    BigDecimal priceCoeff = BigDecimal.ONE.subtract(doubleFee).add(feeSquared);
                    BigDecimal profitCoeff = BigDecimal.ONE.add(MIN_PROFIT);
		            sellFactor = profitCoeff.divide(priceCoeff, MathContext.DECIMAL128);
                    analyzer = ChartAnalyzer.getInstance(); 
                    provider = ChartProvider.getInstance();
                    timeUtils = TimeUtils.getInstance();
                   
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
            }

            private void updateMacdSignals(Price shortEma, Price longEma, final long timestamp)
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
                        if (pendingOrder.getOrderType() == OrderType.BUY)
                        {
                            stopLossPrice = lastPrice.multiply(stopLossFactor);
                            targetBuyPrice = lastPrice.multiply(sellFactor);
                        }
                        else
                        {
                            // TODO
                        }
                    }

                    /*
                    OrderStatus pendingOrderResult = orderBook.checkOrder(pendingOrderId);
                    if (pendingOrderResult != OrderStatus.UNKNOWN) 
                    {
                        boolean tradeDone = 
                                oldCurrencyAmount != null &&
                                oldCurrencyAmount.compareTo(getFunds(currency)) != 0 &&
                                oldPaymentCurrencyAmount != null &&
                                oldPaymentCurrencyAmount.compareTo(getFunds(paymentCurrency)) != 0 
                                
                        if (!tradeDone)
                        {
                            logger.info("order has been executed, but nothing changed!");
                        }
                        else
                        {
                            pendingOrderId = null;
                            lastDeal = pendingOrder;
                            lastPrice = pendingOrder.getPrice();
                            if (pendingOrder.getOrderType() == OrderType.BUY)
                            {
                                stopLossPrice = lastPrice.multiply(stopLossFactor);
                                targetBuyPrice = lastPrice.multiply(sellFactor);
                            }
                            else
                            {
                                // TODO
                            }
                        }
                        if (pendingOrderResult == OrderStatus.PARTIALLY_FILLED)
                        {
                            logger.info("cancelling partially filled order");
                            if (orderBook.cancelOrder(pendingOrder))
                            {
                                pendingOrderId = null;
                            }
                        }
                    }*/
                }                        
            }
            
            private void calculateSignals()
            {
   	            depth = provider.getDepth(_tradeSite, _tradedCurrencyPair);
                buyPrice = depth.getBuy(0).getPrice();
                sellPrice = depth.getSell(0).getPrice();
                System.out.println("div1");
                BigDecimal meanPrice = buyPrice.add(sellPrice).divide(TWO, MathContext.DECIMAL128);
                shortEma = analyzer.getEMA(_tradeSite, _tradedCurrencyPair, EMA_SHORT_INTERVAL);
                longEma = analyzer.getEMA(_tradeSite, _tradedCurrencyPair, EMA_LONG_INTERVAL);
                updateMacdSignals(shortEma, longEma, timeUtils.getCurrentGMTTimeMicros());                
                System.out.println("div2");
                relMacd = macd.divide(meanPrice, MathContext.DECIMAL128).multiply(THOUSAND);
                deltaMacd = macd.subtract(lastMacd);
               
                /* should a short EMA rise too high above target buy price, move stop loss up too */
                if (shortEma.compareTo(targetBuyPrice) > 0)
                {
                    stopLossPrice = sellPrice.multiply(stopLossFactor, MathContext.DECIMAL128);
                    logger.info("*** Stop Loss Adjusted ***");
                }
            }

            private void doTrade()
            {
                order = null;
                if (isTimeToBuy()) 
                {
                    //oldCurrencyAmount = getFunds(currency);
                    //oldPaymentCurrencyAmount = getFunds(paymentCurrency);
 			        order = buyCurrency(depth);
                }
                else if (isStopLoss() || isMinProfit()) 
                {
                    //oldCurrencyAmount = getFunds(currency);
                    //oldPaymentCurrencyAmount = getFunds(paymentCurrency);
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
                do
                {
                    depthOrder = depth.getSell(i++);
                    availableAmount = depthOrder.getAmount();
                }
                while (i < sellOrders && availableAmount.compareTo(MIN_TRADE_AMOUNT) < 0);
                
                if (availableAmount.compareTo(MIN_TRADE_AMOUNT) >= 0)
                {
		            // Now check, if we have any funds to buy something.
                    Price sellPrice = depthOrder.getPrice();
			        Amount buyAmount = new Amount(getFunds(payCurrency).divide(sellPrice, MathContext.DECIMAL128));

			        // If the volume is bigger than the min volume, do the actual trade.
			        if (buyAmount.compareTo(MIN_TRADE_AMOUNT) >= 0) 
                    {

			            // Compute the actual amount to trade.
				        Amount orderAmount = availableAmount.compareTo(buyAmount) < 0 ? availableAmount : buyAmount;

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
                    logger.info("amount market can sell at this price is lower than minimum!");
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
                do
                {
                    depthOrder = depth.getBuy(i++);
                    availableAmount = depthOrder.getAmount();
                }
                while (i < buyOrders && availableAmount.compareTo(MIN_TRADE_AMOUNT) < 0);            

                if (availableAmount.compareTo(MIN_TRADE_AMOUNT) >= 0) 
                {
		            // Now check, if we have any funds to sell.
			        Amount sellAmount = new Amount(getFunds(currency));

			        // If the volume is bigger than the min volume, do the actual trade.
                    if (sellAmount.compareTo(MIN_TRADE_AMOUNT) >= 0) 
                    {

                        // Compute the actual amount to trade.
	                    Amount orderAmount = availableAmount.compareTo(sellAmount) < 0 ? availableAmount : sellAmount;

	                    // Create a sell order...
                        Price buyPrice = depthOrder.getPrice();
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
                    logger.info("funds market can buy at this price are lower than minimum!");
                }
                return null;
            }

            private void reportCycleSummary()
            {
                String macdSymbol;
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
                            macd.setScale(12, RoundingMode.CEILING), priceTrend));
                logger.info(String.format("  +-prev         |                 %10s                 |",
                            lastMacd.setScale(12, RoundingMode.CEILING)));
                logger.info(String.format("  +-delta        |                 %10s     [ %s ]       |",
                            deltaMacd.setScale(12, RoundingMode.CEILING), macdTrend));
                logger.info(              "-----------------+------------------------------------------------+");
                lastMacd = macd;
            }

            private void sleepUntilNextCycle(long t1)
            {
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
