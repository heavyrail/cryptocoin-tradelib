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

package  de.andreas_rueckert.trade.site.poloniex.client;

import de.andreas_rueckert.trade.*;

/**
 * This class holds the data of a currency pair.
 */
public class PoloniexCurrencyPairImpl extends CurrencyPairImpl {

    // Constructors

    /**
     * Create a new currency pair object.
     *
     * @param currency The queried currency.
     * @param paymentCurrency The currency to be used for the payments.
     */
    public PoloniexCurrencyPairImpl(Currency currency, Currency paymentCurrency) 
    {
	    super(currency, paymentCurrency);
    //_currency = currency;
	//_paymentCurrency = paymentCurrency;
    }
    
    // Methods

    /**
     * Convert a string to a CurrencyPairImpl object.
     * 
     * @param currencyPairString The string to convert.
     *
     * @return A CurrencyPairImpl or null, if no matching currency pair was found.
     */
    public static CurrencyPairImpl findByString(String currencyPairString) 
    {
    	String [] currencies = currencyPairString.split( "<=>");
	    if( currencies.length != 2) 
        {
	        throw new CurrencyNotSupportedException("Cannot split " + currencyPairString + " into a currency pair");
	    }

	    // Convert the 2 string into Currency objects.
	    Currency currency = CurrencyImpl.findByString(currencies[0]);
	    Currency paymentCurrency = CurrencyImpl.findByString(currencies[1]);

	    // Create a new currency pair and return it.
	    return new PoloniexCurrencyPairImpl(currency, paymentCurrency);
    }

}
