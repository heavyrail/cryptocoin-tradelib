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
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Scanner;
import java.io.IOException;
import java.io.File;

import de.andreas_rueckert.trade.site.btc_e.client.BtcEClient;
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
    private final static BigDecimal MIN_PROFIT = new BigDecimal("0.00098402");

    private final static BigDecimal LOSS_TO_STOP = new BigDecimal("0.08");

    private final static BigDecimal PROFIT_TO_TAKE = new BigDecimal("0.25");

    /**
     * The minimal trade volume.
     */
    private final static BigDecimal MIN_TRADE_AMOUNT = new Amount("0.1");

    /**
     * The interval for the SMA value.
     */
    //private final static long SMA_INTERVAL = 3L * 60L * 60L * 1000000L; // 3 hrs for now

    /**
     * The interval to update the bot activities.
     */
    private final static int UPDATE_INTERVAL = 60;  // 60 seconds for now...
    
    /*private final static long SMA_CYCLES = 7L; // 124 184 my wife is witch
    private final static long LONG_SMA_CYCLES = 30L;
    private final static long SMA_INTERVAL = SMA_CYCLES * UPDATE_INTERVAL * 1000000L;
    private final static long LONG_SMA_INTERVAL = LONG_SMA_CYCLES * UPDATE_INTERVAL * 1000000L;
    */

    private final static String EMA_SHORT_INTERVAL = "7m";
    private final static String EMA_LONG_INTERVAL = "30m";
    private final static String MACD_SHORT_INTERVAL = "12m";
    private final static String MACD_LONG_INTERVAL = "26m";
    private final static String MACD_SMA_INTERVAL = "9m";

    /* Maximum number of attempts to retry an order failed due to insufficient funds/depth */
    //private final static int MAX_PENDING_ATTEMPTS = 5;

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

    private int cycleNum;

    // Constructors

    /**
     * Create a new bot instance.
     */
    public MaBot() {

	// Set trade site and currency pair to trade.
	//_tradeSite = ModuleLoader.getInstance().getRegisteredTradeSite( "BtcE");
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
    _tradeSite = ModuleLoader.getInstance().getRegisteredTradeSite( "BTCe");
    
    PersistentPropertyList settings = new PersistentPropertyList();
    settings.add(new PersistentProperty("Key", null, _tradeSiteUserAccount.getAPIkey(), 0));
    settings.add(new PersistentProperty("Secret", null, _tradeSiteUserAccount.getSecret(), 0));
    _tradeSite.setSettings(settings);
	_tradedCurrencyPair = CurrencyPairImpl.findByString("LTC<=>USD");
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
     * @param propertyName The name of the property.
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

            Price shortEma = null;
            Price longEma = null;
            Price macd = null;
            Price lastMacd = null;
            Price deltaMacd = null;
            Price buyPrice = null;
            Price sellPrice = null;
            boolean shortEmaAbove;
            boolean upsideDown;
            boolean downsideUp;
            boolean macdUpsideDown;
            boolean macdDownsideUp;
            Order order;
            Order lastDeal;
            ChartAnalyzer analyzer = null;
            Depth depth;
            BigDecimal sellFactor;
            BigDecimal stopLossFactor;
            BigDecimal takeProfitFactor;
            BigDecimal oldCurrencyAmount = null;
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
                        checkOldOrders();
                        calculateSignals();
                        doTrade();
                        cycleNum++;
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

            /*private void decrementPendingSellAttempts()
            {
                pendingSellAttempts = pendingSellAttempts == 0 ? MAX_PENDING_ATTEMPTS : pendingSellAttempts - 1;
            }*/

            private void initTrade()
            {
                //BigDecimal numberOne = new BigDecimal("1"); 
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
                    BigDecimal doubleFee = fee.add(fee);
                    BigDecimal feeSquared = fee.multiply(fee, MathContext.DECIMAL128);
                    BigDecimal priceCoeff = BigDecimal.ONE.subtract(doubleFee).add(feeSquared);
                    BigDecimal profitCoeff = BigDecimal.ONE.add(MIN_PROFIT);
		            sellFactor = profitCoeff.divide(priceCoeff, MathContext.DECIMAL128);
                    analyzer = ChartAnalyzer.getInstance(); 
                    shortEma = analyzer.getEMA(_tradeSite, _tradedCurrencyPair, EMA_SHORT_INTERVAL);
                    longEma = analyzer.getEMA(_tradeSite, _tradedCurrencyPair, EMA_LONG_INTERVAL);
                    macd = shortEma.subtract(longEma);
                    lastMacd = macd;
                    depth = ChartProvider.getInstance().getDepth(_tradeSite, _tradedCurrencyPair); 
                    lastPrice = depth.getSell(0).getPrice();
                    targetBuyPrice = lastPrice.multiply(sellFactor, MathContext.DECIMAL128);
                    stopLossPrice = lastPrice.multiply(stopLossFactor, MathContext.DECIMAL128);
                    BigDecimal currencyValue = getFunds(currency);                                                                                                            
                    BigDecimal payCurrencyValue = getFunds(payCurrency);                                                                                                      
                    initialAssets = depth.getBuy(0).getPrice().multiply(currencyValue).multiply(BigDecimal.ONE.subtract(fee).add(payCurrencyValue));                                 
                    initialAssetsString = initialAssets.setScale(8, RoundingMode.CEILING);
                    cycleNum = 1;
                    shortEmaAbove = shortEma.compareTo(longEma) > 0;
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

            private void checkOldOrders()
            {
                if (pendingOrderId != null) 
                {
                    Order pendingOrder = orderBook.getOrder(pendingOrderId);
                    OrderStatus pendingOrderResult = orderBook.checkOrder(pendingOrderId);
                    if (pendingOrderResult == OrderStatus.PARTIALLY_FILLED)
                    {
                        logger.info("cancelling partially filled order");
                        if (orderBook.cancelOrder(pendingOrder))
                        {
                            pendingOrderId = null;
                        }
                        /*if (pendingOrder.getOrderType() == OrderType.SELL)
                        {
                            //decrementPendingSellAttempts();
                            logger.info("will try to sell again next time");
                        }
                        else
                        {
                            logger.info("will try to buy again next time");
                        }*/
                    }
                    else if (pendingOrderResult != OrderStatus.UNKNOWN && oldCurrencyAmount != null &&
                        oldCurrencyAmount.compareTo(getFunds(currency)) == 0)
                    {
                        logger.info("order has been executed, but nothing changed!");
                        /*if (pendingOrder.getOrderType() == OrderType.SELL)
                        {
                            decrementPendingSellAttempts();
                            logger.info("will try to sell again next time");
                        }
                        else
                        {
                            logger.info("will try to buy again next time");
                        }*/
                    }
                    else
                    {
                        pendingOrderId = null;
                    }
                }                        
            }


            private void calculateSignals()
            {
                shortEma = analyzer.getEMA(_tradeSite, _tradedCurrencyPair, EMA_SHORT_INTERVAL);
                longEma = analyzer.getEMA(_tradeSite, _tradedCurrencyPair, EMA_LONG_INTERVAL);
                macd = shortEma.subtract(longEma);
                deltaMacd = macd.subtract(lastMacd);
  	            depth = ChartProvider.getInstance().getDepth(_tradeSite, _tradedCurrencyPair);
                buyPrice = depth.getBuy(0).getPrice();
                sellPrice = depth.getSell(0).getPrice();
                
                /* should a buy price rise too high, move target buy price up too */
                BigDecimal nextTargetBuyPrice = targetBuyPrice.multiply(sellFactor, MathContext.DECIMAL128);
                if (buyPrice.compareTo(nextTargetBuyPrice) > 0)
                {
                    targetBuyPrice = nextTargetBuyPrice;
                    stopLossPrice = stopLossPrice.multiply(sellFactor, MathContext.DECIMAL128);
                    logger.info("*** Thresholds Moved Up ***");
                }

                boolean newShortEmaAbove =  shortEma.compareTo(longEma) > 0; 
                downsideUp = !shortEmaAbove && newShortEmaAbove;
                upsideDown = shortEmaAbove && shortEma.compareTo(longEma) < 0;
                shortEmaAbove = newShortEmaAbove;
                macdUpsideDown = lastMacd.signum() > 0 && macd.signum() < 0;
                macdDownsideUp = lastMacd.signum() < 0 && macd.signum() > 0;
                lastMacd = macd;
            }

            private void doTrade()
            {
                order = null;
                if (isTimeToBuy()) 
                {
                    oldCurrencyAmount = getFunds(currency);
 			        order = buyCurrency(depth);
                    if (order != null)
                    {
                        pendingOrderId = order.getId();
                    }
                }
                else if (/*isTimeToSell() || */isStopLoss() || isMinProfit()) 
                {
                    oldCurrencyAmount = getFunds(currency);
	                order = sellCurrency(depth); 
                    if (order != null)
                    {
                        pendingOrderId = order.getId();
                    }
                }
                try
                {
                    reportCycleSummary();
                }
                catch (Exception e)
                {
                    logger.error(e);
                }
            }

            /*private boolean isTakeProfit()
            {
                if (lastPrice == null)
                {
                    return false;
                }
                boolean result = buyPrice.compareTo(lastPrice.multiply(takeProfitFactor)) > 0 && macd.signum() > 0;
                if (result)
                {
                    logger.info("*** Take Profit ***");
                }
                return result;
            }*/

            private boolean isStopLoss()
            {
                if (buyPrice.compareTo(stopLossPrice) < 0 && macd.signum() < 0)
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
                boolean result = false;
                if (targetBuyPrice != null && buyPrice.compareTo(targetBuyPrice) > 0 && isTrendDown())
                {
                    logger.info("*** Minimal Profit ***");
                    return true;
                }
                else
                {
                    return false;
                }
            }

            private boolean isTrendDown()
            {
                return macdUpsideDown || (macd.signum() < 0 && deltaMacd.signum() < 0);
            }

            private boolean isTrendUp()
            {
                return macdDownsideUp || (macd.signum() > 0 && deltaMacd.signum() > 0);
            }
            
            /*private boolean isTimeToSell()
            {
                if (isTrendDown())
                {
                    // we would retry sell order only if trend is still down
                    boolean needToRetry = pendingSellAttempts > 0;
                    if (needToRetry)
                    {
                        logger.info(String.format("*** Time To Sell [%d] ***", pendingSellAttempts));
                        return true;
                    }
                }
                return false;
            }*/
            
            private boolean isTimeToBuy()
            {
                boolean result = isTrendUp();
                if (result)
                {
                    logger.info("*** Time To Buy ***");
                }
                return result;
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
                        Order result = orderBook.getOrder(orderId);
                        if (result != null && result.getStatus() != OrderStatus.ERROR);
                        {
                            lastPrice = sellPrice;
                            stopLossPrice = sellPrice.multiply(stopLossFactor);
                            targetBuyPrice = sellPrice.multiply(sellFactor);
                            return result;
                        }
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
                        Order result = orderBook.getOrder(orderId);
                        if (result != null && result.getStatus() != OrderStatus.ERROR);
                        {
                            lastPrice = buyPrice;
                            //pendingSellAttempts = 0;
                            return result;
                        }
                    }
                    else
                    {
                        logger.info("your funds to sell are lower than minimum!");
                        //pendingSellAttempts = 0;
                    }
		        }
                else
                {
                    logger.info("funds market can buy at this price are lower than minimum!");
                }
                //decrementPendingSellAttempts();
                return null;
            }

            private void reportCycleSummary()
            {
                logger.info(String.format("trend            |                                    [ %s ]       |", shortEmaAbove ? "+" : "-"));
                if (order != null)
                {
                    logger.info(String.format("current deal     | %s", order));
                    lastDeal = order;
                }
                else
                {
                    logger.info("current deal     |");
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
                String priceTrend = macd.signum() > 0 ? "+" : "-";
                String macdTrend = deltaMacd.signum() > 0 ? "+" : "-";
                BigDecimal currencyValue = getFunds(currency);
                BigDecimal payCurrencyValue = getFunds(payCurrency);
                BigDecimal currentAssets = buyPrice.multiply(currencyValue).multiply(BigDecimal.ONE.subtract(fee).add(payCurrencyValue));
                BigDecimal profit = currentAssets.subtract(initialAssets);
                BigDecimal profitPercent = profit.divide(initialAssets, MathContext.DECIMAL128);
                BigDecimal uptimeDays = new BigDecimal(cycleNum * UPDATE_INTERVAL / 86400.0);
                BigDecimal profitPercentPerDay = profitPercent.divide(uptimeDays, MathContext.DECIMAL128);
                BigDecimal profitPercentPerMonth = profitPercentPerDay.multiply(new BigDecimal(30));
                BigDecimal profitPercentPerYear = profitPercentPerDay.multiply(new BigDecimal(365));

                logger.info(String.format("days uptime      |                  %12s                  |", uptimeDays.setScale(4, RoundingMode.CEILING)));
                logger.info(String.format("initial ( %4s ) |                  %12s                  |", payCurrency, initialAssetsString));
                logger.info(String.format("current ( %4s ) |                  %12s                  |", payCurrency, currentAssets.setScale(8, RoundingMode.CEILING)));
                logger.info(String.format("profit ( %4s )  |                  %12s                  |", payCurrency, profit.setScale(8, RoundingMode.CEILING)));
                logger.info(String.format("profit (%%)       |                  %12s                  |", profitPercent.setScale(4, RoundingMode.CEILING)));
                logger.info(String.format("        +-day    |                  %12s                  |", profitPercentPerDay.setScale(3, RoundingMode.CEILING)));
                logger.info(String.format("        +-month  |                  %12s                  |", profitPercentPerMonth.setScale(2, RoundingMode.CEILING)));
                logger.info(String.format("        +-year   |                  %12s                  |", profitPercentPerYear.setScale(1, RoundingMode.CEILING)));

                logger.info(String.format("%3s              |                  %12s                  |", currency, currencyValue.setScale(6, RoundingMode.CEILING)));
                logger.info(String.format("%3s              |                  %12s                  |", payCurrency, payCurrencyValue.setScale(6, RoundingMode.CEILING)));
                if (targetBuyPrice != null)
                {
                    logger.info(String.format("buy              | [ %12f ] %12f [ %12f ] |", stopLossPrice, buyPrice, targetBuyPrice));
                }
                else
                {
                    logger.info(String.format("buy              |                  %12f                  |", buyPrice));
                }
                logger.info(String.format("sell             |                  %12f                  |", sellPrice));
                logger.info(String.format("ema-%3s          |                  %12f                  |", EMA_SHORT_INTERVAL, shortEma));
                logger.info(String.format("ema-%3s          |                  %12f                  |", EMA_LONG_INTERVAL, longEma));
                logger.info(String.format("macd             |                  %12f      [ %s ]       |", macd, priceTrend));
                logger.info(String.format("  +-prev         |                  %12f                  |", lastMacd));
                logger.info(String.format("  +-delta        |                  %12f      [ %s ]       |", deltaMacd, macdTrend));
                logger.info(              "-----------------+------------------------------------------------+");
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
