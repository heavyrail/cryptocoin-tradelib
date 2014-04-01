/**
 * Java implementation for cryptocoin trading.
 *
 * Copyright (c) 2013 the authors:
 * 
 * @author Andreas Rueckert <mail@andreas-rueckert.de>
 * @author gosucymp <gosucymp@gmail.com>
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

package de.andreas_rueckert.trade.site.poloniex.client;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

import org.apache.commons.codec.binary.Hex;
import org.jsoup.Connection.Method;
import org.jsoup.Connection.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import de.andreas_rueckert.MissingAccountDataException;
import de.andreas_rueckert.NotYetImplementedException;
import de.andreas_rueckert.persistence.PersistentProperty;
import de.andreas_rueckert.persistence.PersistentPropertyList;
import de.andreas_rueckert.trade.CryptoCoinTrade;
import de.andreas_rueckert.trade.Currency;
import de.andreas_rueckert.trade.CurrencyImpl;
import de.andreas_rueckert.trade.CurrencyNotSupportedException;
import de.andreas_rueckert.trade.CurrencyPair;
import de.andreas_rueckert.trade.CurrencyPairImpl;
import de.andreas_rueckert.trade.Depth;
import de.andreas_rueckert.trade.Price;
import de.andreas_rueckert.trade.TradeDataNotAvailableException;
import de.andreas_rueckert.trade.account.CryptoCoinAccount;
import de.andreas_rueckert.trade.account.CryptoCoinAccountImpl;
import de.andreas_rueckert.trade.account.TradeSiteAccount;
import de.andreas_rueckert.trade.account.TradeSiteAccountImpl;
import de.andreas_rueckert.trade.order.CryptoCoinOrderBook;
import de.andreas_rueckert.trade.order.DepositOrder;
import de.andreas_rueckert.trade.order.OrderNotInOrderBookException;
import de.andreas_rueckert.trade.order.OrderStatus;
import de.andreas_rueckert.trade.order.OrderType;
import de.andreas_rueckert.trade.order.SiteOrder;
import de.andreas_rueckert.trade.order.WithdrawOrder;
import de.andreas_rueckert.trade.site.TradeSite;
import de.andreas_rueckert.trade.site.TradeSiteImpl;
import de.andreas_rueckert.trade.site.TradeSiteRequestType;
import de.andreas_rueckert.trade.site.TradeSiteUserAccount;
import de.andreas_rueckert.util.HttpUtils;
import de.andreas_rueckert.util.LogUtils;
import de.andreas_rueckert.util.TimeUtils;


/**
 * Main class for the poloniex API.
 *
 * @see https://poloniex.com/page/2
 * @see http://bitcoin.stackexchange.com/questions/1393/does-poloniex-have-an-api-for-alternate-currencies
 */
public class PoloniexClient extends TradeSiteImpl implements TradeSite {

    // Static variables

    /**
     * The domain of the service.
     */
    public static String DOMAIN = "www.poloniex.com";

    /**
     * This is just a dummy, since poloniex fails with nonce=currentTimeMicros() and
     * the millis can't be used, since 2 calls might happen within the same millisecond
     *, but the nonce has to be increased. So I start with a unixtime nonce (secs since
     * the epoch and increase for every request by 1).
     */
    private static long _nonce;

    /**
     * This flag is intended for ability to retry the request in the case nonce lose synchronization with the server
     * (see usage below in the code)
     */
    private static boolean retryOnFail = true;
    
    
    /**
     * The default user agent.
     */
    private static String USERAGENT = "Mozilla";

    /**
     * The default timeout.
     */
    private static int TIMEOUT = 3000;


    // Instance variables

    /**
     * The username of the user at poloniex.com
     */
    private String _username = null;

    /**
     * The cookies of the current session.
     */
    Map<String, String> _currentCookies = null;

    /**
     * The currency, that the user uses for payments.
     */
    private Currency _currentCurrency = null;

    /**
     * The current token from the poloniex website. Seems to be 
     * constant during a login session?
     */
    private String _currentToken = null;

    /**
     * The customer id (or number) is actually an (currently 4-digit) int ,
     * but this might change in the future.
     */
    private String _customerId = null;

    /**
     * The HTML parser for the poloniex.com website.
     */
    private PoloniexHtmlParser _htmlParser = null;

    /**
     * Flag to indicate, if the user is logged in.
     */
    private boolean _isLoggedIn = false;

    /**
     * The API key.
     */
    private String _key = null;

    /**
     * The password of the user at poloniex.com
     */
    private String _password = null;

    /**
     * The API secret.
     */
    private String _secret = null;
    
    /**
     *      * The traded currency pair.
     *           */
    CurrencyPair _tradedCurrencyPair;

    /**
     * BTC-E api info url
     */
    private static final String API_URL_INFO = "https://" + DOMAIN + "/public?command=returnTicker";


    // Constructors

    /**
     * Create a new connection to the poloniex.com website.
     */
	public PoloniexClient() {
		super();

		_name = "Poloniex";
		_url = "https://poloniex.com/";

		
		// Define the supported currency pairs for this trading site.
		initSupportedCurrencyPairs();
		//System.out.println(currencyPairFeeTrade);

		setCurrentCurrency( CurrencyImpl.BTC);

		// Create a new parser for the poloniex.com website.
		_htmlParser = new PoloniexHtmlParser( this);

		// Create a unixtime nonce for the new API.
		_nonce = ( TimeUtils.getInstance().getCurrentGMTTimeMicros() / 1000000);
	}


	// Methods

	/**
	 * Initialization of the supported currency pairs for poloniex.
	 */
	private void initSupportedCurrencyPairs() {
		if( !updateSupportedCurrencyPairs()) {
			initDefaultSupportedCurrencyPairs();
		}
	}
	
	/**
	 * Update the supported currency pairs trades. 
	 * @return true if update is made
	 */
	public boolean updateSupportedCurrencyPairs() {
		String requestResult = HttpUtils.httpGet(API_URL_INFO);
		if( requestResult != null) {
			//update the supported currency pairs
			List<CurrencyPairImpl> currencyPairs = new ArrayList<CurrencyPairImpl>();
			JSONObject jsonResult = JSONObject.fromObject( requestResult);

			Iterator itPairs = ((JSONObject) jsonResult).keys();
			String pair;
			String currency;
			String paymentCurrency;
			String[] currencyDetail = new String[2];
			CurrencyImpl currencyObject;
			CurrencyImpl paymentCurrencyObject;
			CurrencyPairImpl currencyPair;
			while(itPairs.hasNext()){
				Object current = itPairs.next();
				pair = (String) current;
				//format is btc_usd, nvc_usd, ftc_btc, etc...
				currencyDetail = pair.split("_");
				currency = currencyDetail[1].toUpperCase();
				paymentCurrency = currencyDetail[0].toUpperCase();
				currencyObject = CurrencyImpl.findByString(currency);
				paymentCurrencyObject = CurrencyImpl.findByString(paymentCurrency);
                if (currencyObject != null && paymentCurrencyObject != null)
                {
				    currencyPair = new CurrencyPairImpl(currencyObject, paymentCurrencyObject);
				    currencyPairs.add(currencyPair);
                }
			}
			_supportedCurrencyPairs = (CurrencyPairImpl []) currencyPairs.toArray(new CurrencyPairImpl[currencyPairs.size()]);
			return true;
		}
		return false;
	}
	
	/**
	 * Initialization of the supported currency pairs for poloniex with default values.
	 */
	private void initDefaultSupportedCurrencyPairs() {
		_supportedCurrencyPairs = new CurrencyPair[18];
		_supportedCurrencyPairs[0] = new CurrencyPairImpl( CurrencyImpl.BTC, CurrencyImpl.USD);
		_supportedCurrencyPairs[1] = new CurrencyPairImpl( CurrencyImpl.BTC, CurrencyImpl.RUR);
		_supportedCurrencyPairs[2] = new CurrencyPairImpl( CurrencyImpl.BTC, CurrencyImpl.EUR);
		_supportedCurrencyPairs[3] = new CurrencyPairImpl( CurrencyImpl.LTC, CurrencyImpl.BTC);
		_supportedCurrencyPairs[4] = new CurrencyPairImpl( CurrencyImpl.LTC, CurrencyImpl.USD);
		_supportedCurrencyPairs[5] = new CurrencyPairImpl( CurrencyImpl.LTC, CurrencyImpl.RUR);
		_supportedCurrencyPairs[6] = new CurrencyPairImpl( CurrencyImpl.LTC, CurrencyImpl.EUR);
		_supportedCurrencyPairs[7] = new CurrencyPairImpl( CurrencyImpl.NMC, CurrencyImpl.BTC);
		_supportedCurrencyPairs[8] = new CurrencyPairImpl( CurrencyImpl.NMC, CurrencyImpl.USD);
		_supportedCurrencyPairs[9] = new CurrencyPairImpl( CurrencyImpl.NVC, CurrencyImpl.BTC);
		_supportedCurrencyPairs[10] = new CurrencyPairImpl( CurrencyImpl.NVC, CurrencyImpl.USD);
		_supportedCurrencyPairs[11] = new CurrencyPairImpl( CurrencyImpl.USD, CurrencyImpl.RUR);
		_supportedCurrencyPairs[12] = new CurrencyPairImpl( CurrencyImpl.EUR, CurrencyImpl.USD);
		_supportedCurrencyPairs[13] = new CurrencyPairImpl( CurrencyImpl.TRC, CurrencyImpl.BTC);
		_supportedCurrencyPairs[14] = new CurrencyPairImpl( CurrencyImpl.PPC, CurrencyImpl.BTC);
		_supportedCurrencyPairs[15] = new CurrencyPairImpl( CurrencyImpl.PPC, CurrencyImpl.USD);
		_supportedCurrencyPairs[16] = new CurrencyPairImpl( CurrencyImpl.FTC, CurrencyImpl.BTC);
		_supportedCurrencyPairs[17] = new CurrencyPairImpl( CurrencyImpl.XPM, CurrencyImpl.BTC);
		
	}

    public void setCurrencyPair(CurrencyPair currencyPair)
    {
        _tradedCurrencyPair = currencyPair;
    }

    // Methods

    /**
     * Execute a authenticated query on poloniex.
     *
     * @param method The method to execute.
     * @param arguments The arguments to pass to the server.
     * @param userAccount The user account on the exchange, or null if the default account should be used.
     *
     * @return The returned data as JSON or null, if the request failed.
     *
     * @see http://pastebin.com/K25Nk2Sv
     */
    private final synchronized JSONObject authenticatedHTTPRequest( String method, Map<String, String> arguments, TradeSiteUserAccount userAccount) {
	HashMap<String, String> headerLines = new HashMap<String, String>();  // Create a new map for the header lines.
	Mac mac;
	SecretKeySpec key = null;
	String accountKey;     // The used key of the account.
	String accountSecret;  // The used secret of the account.

	// Try to get an account key and secret for the request.
	if( userAccount != null) {

	    accountKey = userAccount.getAPIkey();
	    accountSecret = userAccount.getSecret();

	} else {  // Use the default values from the API implementation.

	    accountKey = _key;
	    accountSecret = _secret;
	}

	// Check, if account key and account secret are available for the request.
	if( accountKey == null) {
	    throw new MissingAccountDataException( "Key not available for authenticated request to poloniex");
	}
	if( accountSecret == null) {
	    throw new MissingAccountDataException( "Secret not available for authenticated request to poloniex");
	}

	if( arguments == null) {  // If the user provided no arguments, just create an empty argument array.
	    arguments = new HashMap<String, String>();
	}
	
	arguments.put( "command", method);  // Add the method to the post data.
	arguments.put( "nonce",  "" + ++_nonce);  // Add the dummy nonce.

	// Convert the arguments into a string to post them.
	String postData = "";

	for( Iterator argumentIterator = arguments.entrySet().iterator(); argumentIterator.hasNext(); ) {
	    Map.Entry argument = (Map.Entry)argumentIterator.next();
	    
	    if( postData.length() > 0) {
		postData += "&";
	    }
	    postData += argument.getKey() + "=" + argument.getValue();
	}

	// Create a new secret key
	try {

	    key = new SecretKeySpec( accountSecret.getBytes( "UTF-8"), "HmacSHA512" ); 

	} catch( UnsupportedEncodingException uee) {

	    System.err.println( "Unsupported encoding exception: " + uee.toString());
        retryOnFail = true; // ensure next time we call this method, it would have the ability to retry the request if needed
	    return null;
	} 

	// Create a new mac
	try {

	    mac = Mac.getInstance( "HmacSHA512" );

	} catch( NoSuchAlgorithmException nsae) {

	    System.err.println( "No such algorithm exception: " + nsae.toString());
        retryOnFail = true;
	    return null;
	}

	// Init mac with key.
	try {
	    mac.init( key);
	} catch( InvalidKeyException ike) {
	    System.err.println( "Invalid key exception: " + ike.toString());
        retryOnFail = true;
	    return null;
	}

	// Add the key to the header lines.
	headerLines.put( "Key", accountKey);

	// Encode the post data by the secret and encode the result as base64.
	try {

	    headerLines.put( "Sign", Hex.encodeHexString( mac.doFinal( postData.getBytes( "UTF-8"))));
	} catch( UnsupportedEncodingException uee) {

	    System.err.println( "Unsupported encoding exception: " + uee.toString());
        retryOnFail = true;
	    return null;
	} 
	
	// Now do the actual request
	String requestResult = HttpUtils.httpPost( "https://" + DOMAIN + "/tradingApi", headerLines, postData);

	if( requestResult != null) 
    {   // The request worked

	    try 
        {
		// Convert the HTTP request return value to JSON to parse further.
		    JSONObject jsonResult = JSONObject.fromObject(requestResult);
            return jsonResult;
        }
        catch (JSONException e)
        {
            try
            {
                JSONObject finalResult = new JSONObject();
                JSONArray jsonResult = JSONArray.fromObject(requestResult);
                for (Iterator it = jsonResult.iterator(); it.hasNext(); ) 
                {
                    JSONObject record = (JSONObject) it.next();
                    String keyField = record.names().getString(0);
                    String id = record.getString(keyField);
                    finalResult.put(id, record);
                }
                return finalResult;
            }
            catch (JSONException je)
            {
		        System.err.println( "Cannot parse json request result: " + je.toString());
                return null;  // An error occured...
            }
        }
    }
    return null;
    }

    /**
     * Cancel an order on the trade site.
     *
     * @param order The order to cancel.
     *
     * @return true, if the order was canceled. False otherwise.
     */
    public boolean cancelOrder(SiteOrder order) 
    {

	    // The parameters for the HTTP post call.
	    HashMap<String, String> parameter = new HashMap<String, String>();

	    // Get the site id of this order.
	    String site_id = order.getSiteId();

	    // If there is no site id, we cannot cancel the order.
	    if (site_id == null) 
        {
	        return false;
	    }
	
	    parameter.put("orderNumber", order.getSiteId());  // Pass the site id of the order.
        parameter.put("currencyPair", _tradedCurrencyPair.getPaymentCurrency().getName().toUpperCase() + "_" +
                _tradedCurrencyPair.getCurrency().getName().toUpperCase());

	    JSONObject jsonResponse = authenticatedHTTPRequest("cancelOrder", parameter, order.getTradeSiteUserAccount());

	    if (jsonResponse == null) 
        {
	        LogUtils.getInstance().getLogger().error("No response from poloniex while attempting to cancel an order");
	        return false;
    	} 
        else
        {
	        return true; // Ok!
	    }
    }

    /**
     * Execute an order on the trade site.
     * Synchronize this method, since several users might execute orders in parallel via an API implementation instance.
     *
     * @param order The order to execute.
     *
     * @return The new status of the order.
     */
    public synchronized OrderStatus executeOrder(SiteOrder order) 
    {
        OrderType orderType = order.getOrderType();  // Get the type of this order.

	    if ((orderType == OrderType.BUY) || ( orderType == OrderType.SELL)) 
        {  // If this is a buy or sell order, run the trade code.

	        // The parameters for the HTTP post call.
	        HashMap<String, String> parameter = new HashMap<String, String>();
	    
	        parameter.put("amount", formatAmount( order.getAmount()));
	        parameter.put("rate", formatPrice( order.getPrice(), order.getCurrencyPair()));
	        parameter.put("currencyPair", 
                    order.getCurrencyPair().getPaymentCurrency().getName().toUpperCase() + "_" +
                    order.getCurrencyPair().getCurrency().getName().toUpperCase());  

	        JSONObject jsonResponse = authenticatedHTTPRequest(
                    order.getOrderType() == OrderType.BUY ? "buy" : "sell", parameter, order.getTradeSiteUserAccount());

	        if (jsonResponse == null) 
            {
		        return OrderStatus.ERROR;
	        } 
            else
            {
		        // Try to get and store the site id for the order first, so we can access the order later.
		        long poloniexOrderId = jsonResponse.getLong("orderNumber");
		        order.setSiteId("" + poloniexOrderId);  // Store the id in the order.
                order.setStatus(OrderStatus.PARTIALLY_FILLED);
		        return order.getStatus();
	        }
	    } 
        else if (orderType == OrderType.DEPOSIT) 
        {  // This is a deposit order..
	        throw new NotYetImplementedException( "Executing deposits is not yet implemented for " + this.getName());
	    }
        else if (orderType == OrderType.WITHDRAW) 
        {  // This is a withdraw order.
	        throw new NotYetImplementedException( "Executing withdraws is not yet implemented for " + this.getName());
	    }
	    return null;  // An error occured, or this is an unknow order type?
    }

    /**
     * Format an amount poloniex compliant.
     * 
     * @param amount The amount to format.
     */
    private final String formatAmount(BigDecimal amount) 
    {
	    // The amount has always 8 fraction digits for now.
	    DecimalFormat amountFormat = new DecimalFormat("#########.########", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
	    return amountFormat.format(amount);
    }

    /**
     * Format the price for a given currency pair.
     *
     * @param price The price to format.
     * @param currencyPair The currency pair to trade.
     */
	private final String formatPrice(BigDecimal price, CurrencyPair currencyPair) 
    {
        DecimalFormat f = new DecimalFormat("###.########", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        return f.format(price);
	}

    /**
     * Get the current funds of the user via the new trade API.
     *
     * @param userAccount The account of the user on the exchange. Null, if the default account should be used.
     *
     * @return The accounts with the current balance as a collection of Account objects, or null if the request failed.
     */
    public Collection<TradeSiteAccount> getAccounts( TradeSiteUserAccount userAccount) {

	// Try to get some info on the user (including the current funds).
	JSONObject jsonResponse = authenticatedHTTPRequest( "returnBalances", null, userAccount);

	if( jsonResponse != null) {

	    //JSONObject jsonFunds = jsonResponse.getJSONObject( "funds");  // Get the JSONObject for the funds.

	    // An array for the parsed funds.
	    ArrayList<TradeSiteAccount> result = new ArrayList<TradeSiteAccount>();

	    // Now iterate over all the currencies in the funds.
	    for( Iterator currencyIterator = jsonResponse.keys(); currencyIterator.hasNext(); ) {
		
		String currentCurrency = (String)currencyIterator.next();  // Get the next currency.
		
		BigDecimal balance = new BigDecimal( jsonResponse.getString( currentCurrency));  // Get the balance for this currency.

		result.add( new TradeSiteAccountImpl( balance, CurrencyImpl.findByString( currentCurrency.toUpperCase()), this));
	    }

	    return result; // Return the array with the accounts.
	}

	return null;  // The request failed.
    }

    /**
     * Get the accounts with the current funds on this trading site. This is done via HTML here, since
     * this was implemented before the new Trade API.
     *
     * @return The accounts with the current balance as a collection of Account objects, or null if the request failed.
     */
    public Collection<TradeSiteAccount> getAccountsViaHTML() {
	
	String url = "https://" + PoloniexClient.DOMAIN + "/ajax/" + "profile.php";

	ensureLogin();  // Make sure, that the user is logged in.
	
	if( _customerId == null) {
	    throw new MissingPoloniexCustomerIdException( "getFunds: no customer id received from the poloniex.com website.");
	}
	
	if( _currentCookies == null) {
	    throw new MissingPoloniexCookieException( "No current poloniex.com cookie for getFunds! Please login to get one!");
	}

	try {
	    // Now post the actual profile request as jquery json.
	    Response response = Jsoup.connect( url)
		.data("task", "cass", "data", _customerId)
		.method( Method.POST)
		.cookies( _currentCookies)
		.userAgent( USERAGENT)
		.timeout( TIMEOUT)
		.execute();

	    // Now find the balances in the body.
	    return _htmlParser.findBalances( response.body());

	} catch( IOException ioe) {
	    System.err.println( "Cannot post profile request to the poloniex.com website: " + ioe.toString());
	}

	return null;  // Indicate an error.
    }

    /**
     * Get a page, that requires a logged in user.
     *
     * @param URL The URL of the page.
     *
     * @see http://jsoup.org/cookbook/input/load-document-from-url
     */
    private void getAuthenticatedPage( String URL) {

	try {
	    // Do a HTTP post with the user data to fetch the page.
	    Document doc = Jsoup.connect("https://" + PoloniexClient.DOMAIN).data("query", "Java")
		.userAgent( USERAGENT)
		.cookie("auth", "token")
		.timeout( TIMEOUT)
		.post();
	} catch( IOException ioe) {
	    System.err.println( "Cannot post authenticated page to poloniex.com: " + ioe.toString());
	}
    }

    /**
     * Return the current reference currency of the user.
     *
     * @return The current reference currency.
     */
    public Currency getCurrentCurrency() {
	return _currentCurrency;
    }

    /**
     * Get the poloniex string representation of a currency pair.
     *
     * @param currencyPair The currency pair to convert.
     *
     * @return The currency pair as a poloniex string.
     */
    private String getCurrencyPairString( CurrencyPair currencyPair) {
	return currencyPair.getPaymentCurrency().getName().toUpperCase() + "_" + currencyPair.getCurrency().getName().toUpperCase();
    }

    /**
     * Get an address to deposit coins at poloniex.
     *
     * @param currency The currency to deposit.
     *
     * @return The deposit address as a string.
     */
    private String getDepositAddress( Currency currency) {

	// The URL to request the address from.
	String url = null;

	// Check, if the currency is a FIAT currency.
	if( currency.equals( CurrencyImpl.RUR)
	    || currency.equals( CurrencyImpl.EUR)
	    || currency.equals( CurrencyImpl.USD)) {

	    // The FIAT URLs differ from the cryptocoin URLs...
	    url = "https://poloniex.com/profile#funds/deposit/" + currency.getName().toLowerCase();

	} else {

	    // Compute the cryptocoin URL from the currency id.
	    url = "https://poloniex.com/profile#funds/deposit_coin/" + getIdForCurrency( currency);
	}
	
	ensureLogin();  // Make sure, that the user is logged in.
	
	if( _customerId == null) {
	    throw new MissingPoloniexCustomerIdException( "getDepositAddress: no customer id received from the poloniex.com website.");
	}
	
	if( _currentCookies == null) {
	    throw new MissingPoloniexCookieException( "No current poloniex.com cookie for getDepositAddress! Please login to get one!");
	}


	// Do a authenticate HTTP post request.
	try {
	    // Now post the actual profile request as jquery json.
	    Response response = Jsoup.connect( url)
		.method( Method.GET)
		.cookies( _currentCookies)
		.userAgent( USERAGENT)
		.timeout( TIMEOUT)
		.execute();

	    // Now find the balances in the body.
	    return _htmlParser.findCoinAddress( response.body());

	} catch( IOException ioe) {
	    System.err.println( "Cannot get deposit address from the poloniex.com website: " + ioe.toString());
	}

	return null;  // Indicate an error.
    }

    /**
     * Get the market depth as a Depth object.
     *
     * @param currency The currency to query
     *
     * @return The market depth.
     *
     * @throws TradeDataNotAvailableException if the depth is not available.
     */
    public Depth getDepth( Currency currency) throws TradeDataNotAvailableException {
	return getDepth( new CurrencyPairImpl( currency, CurrencyImpl.BTC));
    }

    /**
     * Get the market depth as a Depth object.
     *
     * @param currencyPair The queried currency pair.
     *
     * @throws TradeDataNotAvailableException if the depth is not available.
     */
    public Depth getDepth( CurrencyPair currencyPair) throws TradeDataNotAvailableException {

	if( ! isSupportedCurrencyPair( currencyPair)) {
	    throw new CurrencyNotSupportedException( "Currency pair: " + currencyPair.toString() + " is currently not supported on Poloniex");
	}

	String url = "https://" + DOMAIN + "/public?command=returnOrderBook&currencyPair=" 
	    + getCurrencyPairString(currencyPair);

	String requestResult = HttpUtils.httpGet( url);

	if( requestResult != null) {  // Request sucessful?
	    try {

		// Convert the HTTP request return value to JSON to parse further.
		return new PoloniexDepth( JSONObject.fromObject( requestResult), currencyPair, this);

	    } catch( JSONException je) {

		System.err.println( "Cannot parse " + this._name + " depth return: " + je.toString());

		throw new TradeDataNotAvailableException( "cannot parse data from " + this._name);
	    }
	}
	
	throw new TradeDataNotAvailableException( this._name + " server did not respond to depth request");
    }
    
     /**
     * Get the market depth as a Depth object with a limit, using api v3. Max limit is 2000 at the moment.
     *
     * @param currencyPair The queried currency pair.
     * @param limit The first {limit} values of the depth
     *
     * @throws TradeDataNotAvailableException if the depth is not available.
     */
    public Depth getDepth( CurrencyPair currencyPair, int limit) throws TradeDataNotAvailableException {

	if( ! isSupportedCurrencyPair( currencyPair)) {
	    throw new CurrencyNotSupportedException( "Currency pair: " + currencyPair.toString() + " is currently not supported on Poloniex");
	}

	String url = "https://" + DOMAIN + "/api/3/depth/" 
	    + getCurrencyPairString( currencyPair)
	    + "?limit=" + limit;

	String requestResult = HttpUtils.httpGet( url);

	if( requestResult != null) {  // Request sucessful?
	    try {
	    JSONObject requestResultObj = (JSONObject) JSONObject.fromObject( requestResult).get(getCurrencyPairString( currencyPair));
		// Convert the HTTP request return value to JSON to parse further.
		return new PoloniexDepth( JSONObject.fromObject( requestResultObj), currencyPair, this);

	    } catch( JSONException je) {

		System.err.println( "Cannot parse " + this._name + " depth return: " + je.toString());

		throw new TradeDataNotAvailableException( "cannot parse data from " + this._name);
	    }
	}
	
	throw new TradeDataNotAvailableException( this._name + " server did not respond to depth request");
    } 

    /**
     * Get the fee for an order in the resulting currency.
     * Synchronize this method, since several users might use this method with different
     * accounts and therefore different fees via a single API implementation instance.
     *
     * @param order The order to use for the fee computation.
     *
     * @return The fee in the resulting currency (currency value for buy, payment currency value for sell).
     */
    public synchronized Price getFeeForOrder( SiteOrder order) {
	    return new Price((new BigDecimal("0.002")).multiply(order.getAmount()), order.getCurrencyPair().getCurrency());

	/*if( order instanceof WithdrawOrder) {

	    if( order.getCurrencyPair().getCurrency().equals( CurrencyImpl.BTC)) {
		return new Price( "0.01");  // Withdrawal in btc seem to cost always 0.01 btc ?
	    } else {
		// System.out.println( "Compute withdaw fees for currencies other than btc");

		throw new CurrencyNotSupportedException( "Cannot compute fee for this order: " + order.toString());
	    }
	} else if(( order.getOrderType() == OrderType.BUY) || ( order.getOrderType() == OrderType.SELL)) {
		return new Price( getFeeForCurrencyPairTrade(order.getCurrencyPair()).multiply(order.getAmount())
				, order.getCurrencyPair().getCurrency());
		
	} else if( order instanceof DepositOrder) {

	    Currency depositedCurrency = ((DepositOrder)order).getCurrency();
	    
	    if( depositedCurrency.equals( CurrencyImpl.BTC)) {
		
		// BTC deposits are free as far as I know.
		return new Price( "0.0", CurrencyImpl.BTC);
	    
	} else {

		throw new NotYetImplementedException( "Deposit fees are not implemented for trade site " 
						      + getName() 
						      + " and currency " 
						      + depositedCurrency.getName());
	    }

	} else {  // Just the default implementation for the other order forms.

	    return super.getFeeForOrder( order);
	}*/
    }
    
    public BigDecimal getFeeForTrade() {
        return new BigDecimal("0.002");
    }

    /**
     * Get id for a pair of currencies.
     * Look at http://bitcoin.stackexchange.com/questions/1393/does-poloniex-have-an-api-for-alternate-currencies
     * for more info.
     *
     * @param currencyPair The currency pair to trade.
     *
     * @return The id for this currency pair, or -1 if the id is not known.
     */
    private short getIdForCurrencies( CurrencyPair currencyPair) {

	if( (CurrencyImpl)currencyPair.getPaymentCurrency() == CurrencyImpl.BTC) {
	    switch( (CurrencyImpl)currencyPair.getCurrency()) {
	    case LTC: return 10;
	    }
	} else if( (CurrencyImpl)currencyPair.getPaymentCurrency() == CurrencyImpl.USD) {
	    switch( (CurrencyImpl)currencyPair.getCurrency()){
	    case BTC: return 1;
	    case LTC: return 14;
	    }
	}

	throw new CurrencyNotSupportedException( "Currency pair: " 
						 + currencyPair.getCurrency().getName() 
						 + " with payment currency: " 
						 + currencyPair.getPaymentCurrency().getName() 
						 + " not supported in PoloniexClient.getIdForCurrencies");

	// return -1;
    }

    /**
     * Get the id of the combination of given currency and current currency.
     *
     * @param currency The currency to query.
     */
    private final short getIdForCurrency( Currency currency) {

	switch( (CurrencyImpl)currency) {
	case BTC: return 1;
	case LTC: return 8;
	case RUC: return 9;
	case NMC: return 10;
	case NVC: return 13;
	}

	throw new CurrencyNotSupportedException( "Currency: " 
						 + currency.getName() 
						 + " not supported in PoloniexClient.getIdForCurrency");
    }

    /**
     * Get the shortest allowed requet interval in microseconds.
     *
     * @return The shortest allowed request interval in microseconds.
     */
    public long getMinimumRequestInterval() {
	return getUpdateInterval();
    }

    /**
     * Get the open orders on this trade site.
     *
     * @param userAccount The account of the user on the exchange. Null, if the default account should be used.
     *
     * @return The open orders as a collection, or null if the request failed.
     */
    public Collection<SiteOrder> getOpenOrders(TradeSiteUserAccount userAccount) 
    {
	    // Set the parameters for the order list request.
	    Map< String, String> parameters = new HashMap< String, String>();
        parameters.put( "currencyPair", _tradedCurrencyPair.getPaymentCurrency().getName().toUpperCase() + "_" +
                _tradedCurrencyPair.getCurrency().getName().toUpperCase());

    	// Try to get some info on the open orders.
        JSONObject jsonResponse = authenticatedHTTPRequest("returnOpenOrders", parameters, userAccount);
	    if (jsonResponse != null) 
        {  // If the request succeeded.
            // Create a buffer for the result.
            ArrayList<SiteOrder> result = new ArrayList<SiteOrder>();

            // The answer is an assoc array with the site id's as the key and a json object with order details as the values.
            for (Iterator keyIterator = jsonResponse.keys(); keyIterator.hasNext(); ) 
            {
                // Get the next site id from the iterator.
                String currentSiteId = (String) (keyIterator.next());

                // Since we know the tradesite and the site id now, we can query the order book for the order.
                SiteOrder currentOrder = CryptoCoinOrderBook.getInstance().getOrder(this, currentSiteId);

                if (currentOrder != null) 
                {     // If the order book returned an order,
                    result.add(currentOrder);  // add it to the result buffer.
		        }
                else
                {  // It seems, this order is not in the order book. I can consider this an error at the moment,
		          // since every order should go through the order book.
		            throw new OrderNotInOrderBookException("Error: poloniex order with site id " + currentSiteId + " is not in order book!");
		        }
	        }
	        return result;  // Return the buffer with the orders.	    
	    } 
	    return null;  // An error occured.
    }

    /**
     * Get the poloniex password of the user.
     *
     * @return The poloniex password of the user.
     */
    public String getPassword() {
	return _password;
    }

    /**
     * Get the section name in the global property file.
     *
     * @return The name of the property section as a String.
     */
    public String getPropertySectionName() {
	return "Poloniex";
    }

    /**
     * Get the settings of the poloniex client.
     *
     * @return The setting of the poloniex client as a list.
     */
    public PersistentPropertyList getSettings() {

	// Get the settings from the base class.
	PersistentPropertyList result = super.getSettings();

	result.add( new PersistentProperty( "Username", null, _username, 8));
	result.add( new PersistentProperty( "Password", null, _password, 7));
	result.add( new PersistentProperty( "Key", null, _key, 6));        // The key
	result.add( new PersistentProperty( "Secret", null, _secret, 5));  // and secret for the new trade API.

	return result;
    }

    /**
     * Get the current ticker from the poloniex API.
     *
     * @param currencyPair The currency pair to query.
     * @param paymentCurrency The currency for the payments.
     *
     * @return The current poloniex ticker.
     *
     * @throws TradeDataNotAvailableException if the ticker is not available.
     */
    public PoloniexTicker getTicker( CurrencyPair currencyPair) throws TradeDataNotAvailableException {

	if( ! isSupportedCurrencyPair( currencyPair)) {
	    throw new CurrencyNotSupportedException( "Currency pair: " + currencyPair.toString() + " is currently not supported on " + this._name);
	}
	
	String url = "https://" + DOMAIN + "/api/2/" 
	    + getCurrencyPairString( currencyPair)
	    + "/ticker";

	String requestResult = HttpUtils.httpGet( url);
	
	if( requestResult != null) {  // Request sucessful?
	    try {
		// Convert the HTTP request return value to JSON to parse further.
		return new PoloniexTicker( JSONObject.fromObject( requestResult), currencyPair, this);
	    } catch( JSONException je) {
		System.err.println( "Cannot parse ticker object: " + je.toString());
	    }
	}
	
	throw new TradeDataNotAvailableException( "The poloniex ticker request failed");
	// return null;  // The ticker request failed.
    }

    /**
     * Get a list of recent trades.
     *
     * @param since_micros The GMT-relative epoch in microseconds.
     * @param currencyPair The currency pair to query.
     *
     * @return The trades as a list of Trade objects.
     *
     * @throws TradeDataNotAvailableException if the ticker is not available.
     */
    public CryptoCoinTrade [] getTrades( long since_micros, CurrencyPair currencyPair) throws TradeDataNotAvailableException {

	if( ! isSupportedCurrencyPair( currencyPair)) {
	    throw new CurrencyNotSupportedException( "Currency pair: " + currencyPair.toString() + " is currently not supported on Poloniex");
	}

	String url = "https://" + DOMAIN + "/public?command=returnTradeHistory&currencyPair=" 
	    + getCurrencyPairString( currencyPair);

	// System.out.println( "Fetching poloniex trades from: " + url);

	CryptoCoinTrade [] tempResult =  getTradesFromURL( url, currencyPair);

	if( tempResult != null) {
	    // Now filter the trades for the timespan.
        long now = System.currentTimeMillis() * 1000L;
        long threshold = now - since_micros;
	    ArrayList<CryptoCoinTrade> resultBuffer = new ArrayList<CryptoCoinTrade>();
	    for( int i = 0; i < tempResult.length; ++i) {
		if( tempResult[i].getTimestamp() > threshold) {
		    resultBuffer.add( tempResult[i]);
		}
	    }
	
	    // Now convert the buffer back to an array and return it.
	    return resultBuffer.toArray( new CryptoCoinTrade[ resultBuffer.size()]);
	}

	throw new TradeDataNotAvailableException( "trades request on poloniex failed");
    }

    /**
     * Get a list of trades from a URL.
     *
     * @param url The url to fetch the trades from.
     * @param currencyPair The requested currency pair.
     *
     * @return A list of trades or null, if an error occurred.
     */
    private CryptoCoinTrade [] getTradesFromURL( String url, CurrencyPair currencyPair) {
	ArrayList<CryptoCoinTrade> trades = new ArrayList<CryptoCoinTrade>();

        String requestResult = HttpUtils.httpGet( url);

	if( requestResult != null) {  // If the HTTP request worked ok.
	    try {
		// Convert the result to an JSON array.
		JSONArray resultArray = JSONArray.fromObject( requestResult);
		
		// Iterate over the array and convert each trade from json to a Trade object.
		for( int i = 0; i < resultArray.size(); i++) {
		    JSONObject tradeObject = resultArray.getJSONObject(i);
		    
		    trades.add( new PoloniexTradeImpl( tradeObject, this, currencyPair));  // Add the new Trade object to the list.
		}

		CryptoCoinTrade [] tradeArray = trades.toArray( new CryptoCoinTrade[ trades.size()]);  // Convert the list to an array.
		
		return tradeArray;  // And return the array.

	    } catch( JSONException je) {
		System.err.println( "Cannot parse trade object: " + je.toString());
	    }
	}

	return null;  // An error occured.
    }

    /**
     * Get the interval, in which the trade site updates it's depth, ticker etc. 
     * in microseconds.
     *
     * @return The update interval in microseconds.
     */
    public long getUpdateInterval() {
	return 15L * 1000000L;  // The default poloniex update happens every 15s, I think.
    }

    /**
     * Get the poloniex username of the user.
     *
     * @return The poloniex username of the user.
     */
    public String getUsername() {
	return _username;
    }

    /**
     * Login the user to poloniex.
     */
    private boolean doUserLogin( String username, String password) {

	if( ( username == null) || "".equals( username)) {
	    throw new MissingPoloniexLoginDataException( "username is null or empty in doUserLogin");
	}

	if( ( password == null) || "".equals( password)) {
	    throw new MissingPoloniexLoginDataException( "password is null or empty in doUserLogin");
	}

	try {
	    // Get the poloniex response to posting the login form.
	    Response res = Jsoup.connect( "https://" + PoloniexClient.DOMAIN + "/login")
		.data( "login", username, "password", password)
		.method( Method.POST)
		.timeout( TIMEOUT)
		.userAgent( USERAGENT)
		.execute();

	    // Get the current cookies from the response.
	    _currentCookies = res.cookies();

	    // Find the customer id in the response.
	    if( ( _customerId = _htmlParser.findCustomerId( res.body())) == null) {  // If we cannot find the customer id in the
		return false;                                                        // response, the login has failed most likely...
	    }

	    // Find the token in the response.
	    if( ( _currentToken =_htmlParser.findToken( res.body())) == null) {  // If the cannot find the token in the response,
		return false;                                                    // the login has failed most likely...
	    }
 
	    // Just print the reponse for further hacking.
	    // System.out.println( "Login reponse: " + res.body());

	    _isLoggedIn = true;  // It seems the login went ok.

	    return true;

	} catch( IOException ioe) {
	    System.err.println( "Cannot connect to the poloniex.com server: " + ioe.toString());
	}	

	return false;  // Login failed.
    }

    /**
     * Make sure, that the user is logged in.
     *
     * @return true, if the login was succesful, false otherwise.
     */
    private boolean ensureLogin() {

	if( ! _isLoggedIn) {  // if we are not logged in yet.

	    doUserLogin( getUsername(), getPassword());
	}

	return false;  // Login failed.
    }

    /**
     * Check, if some request type is allowed at the moment. Most
     * trade site have limits on the number of request per time interval.
     *
     * @param requestType The type of request (trades, depth, ticker, order etc).
     *
     * @return true, if the given type of request is possible at the moment.
     */
    public boolean isRequestAllowed( TradeSiteRequestType requestType) {

	return true;  // Just a dummy for now, but poloniex is quite relaxed on request limits...
    }

    /**
     * Set a new reference currency for the user.
     *
     * @param currency The new currency to use for display of data.
     */
    public void setCurrentCurrency( Currency currency) {
	_currentCurrency = currency;
    }

    /**
     * Set new settings for the poloniex client.
     *
     * @param settings The new settings for the poloniex client.
     */
    public void setSettings( PersistentPropertyList settings) {
	
	super.setSettings( settings);
	
	String key = settings.getStringProperty( "Key");
	if( key != null) {
	    _key = key;  // Get the API key from the settings.
	}
	String secret =  settings.getStringProperty( "Secret");
	if( secret != null) {
	    _secret = secret;  // Get the secret from the settings.
	}
	String user = settings.getStringProperty( "Username");
	if( user != null) {
	    _username = user;  // Get the username from the settings.
	}
	String password = settings.getStringProperty( "Password");
	if( password != null) {
	    _password = password;  // Get the password from the settings.
	}
    }

    /**
     * Return a string for this site (just a name for now).
     * To be used in the project tree.
     */
    public String toString() {
	return getName();
    }
}
