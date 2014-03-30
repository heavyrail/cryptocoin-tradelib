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

package de.andreas_rueckert.trade;


/**
 * This is a minimal currency implementation, that should work
 * across most trading sites.
 */
public enum CurrencyImpl implements Currency {

    /**
     * The values for the minimal currency implementation.
     */
    AIR, ALP, AMC, ANC, APH, AUR,
    BC, BET, BONES, BQC, BTC, BTCS,
    CACH, CASH, CGA, CGB, CIN, CNC, CNOTE, CNY, COL, CON, CORG, CRC,
    DEM, DGC, DIEM, DIME, DOGE, DRK, DTC, DVC,
    EAC, EBT, ECC, EFL, ELC, ELP, EMC2, EMD, EMO, ETOK, EUR, EXE,
    FAC, FLAP, FLT, FOX, FRC, FRK, FRQ, FTC, FZ,
    GBP, GDC, GLB, GLC, GNS, GPUC, GRC,
    H20, HIC, HUC, HVC,
    ICN, IFC, I0C, IXC,
    KDC, KGC,
    LEAF, LTC,
    MAX, MINT, MEC, MEOW, MMC, MRC, MTS, MYR, MZC,
    NAN, NET, NMC, NOBL, NRB, NRS, NVC, NXT,
    OLY, ORB,
    PAND, PAWN, PIG, PLN, PMC, PPC, PRC, PTS,
    Q2C, QRK,
    REC, RED, REDD, RIC, RUC, RUR,
    SBC, SMC, SOC, SPA, SPT, SUN, SXC,
    TAG, TRC,
    UNO, USD, USDE, UTC,
    VTC,
    WDC, WIKI, WOLF,
    XCP, XNC, XPM, XSV,
    YANG, YIN,
    ZET;

    // Methods

    /**
     * Check, if 2 currencies are the same.
     *
     * @param currency The second currency to check for equality.
     *
     * @return true, if the 2 currencies are equal. False otherwise.
     */
    public boolean equals( Currency currency) {
	return getName().equals( currency.getName());
    }

    /**
     * Convert a string to a CurrencyImpl object.
     *
     * @param currencyString The string to convert.
     *
     * @return A CurrencyImpl object or null, if no matching constant was found.
     */
    public static CurrencyImpl findByString(String currencyString) 
    {
	    try 
        {
            return CurrencyImpl.valueOf(currencyString);
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    /**
     * Get the name of this currency.
     *
     * @return The name of this currency.
     */
    public String getName() {
	return name();
    }
}
