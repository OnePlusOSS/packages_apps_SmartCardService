/*
 * Copyright 2012 Giesecke & Devrient GmbH.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.simalliance.openmobileapi.service.security.ara;

import android.util.Log;

import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.MissingResourceException;

import org.simalliance.openmobileapi.service.CardException;
import org.simalliance.openmobileapi.service.IChannel;
import org.simalliance.openmobileapi.service.ISmartcardServiceCallback;
import org.simalliance.openmobileapi.service.ITerminal;
import org.simalliance.openmobileapi.service.SmartcardService;
import org.simalliance.openmobileapi.service.security.AccessControlEnforcer;
import org.simalliance.openmobileapi.service.security.AccessRuleCache;
import org.simalliance.openmobileapi.service.security.ChannelAccess;
import org.simalliance.openmobileapi.service.security.gpac.dataobjects.BerTlv;
import org.simalliance.openmobileapi.service.security.gpac.dataobjects.ParserException;
import org.simalliance.openmobileapi.service.security.gpac.dataobjects.REF_AR_DO;
import org.simalliance.openmobileapi.service.security.gpac.dataobjects.Response_ALL_AR_DO;
import org.simalliance.openmobileapi.service.security.gpac.dataobjects.Response_DO_Factory;

public class AraController {

	private AccessControlEnforcer mMaster = null;
    private AccessRuleCache mAccessRuleCache = null;

    private ITerminal mTerminal = null;
    private AccessRuleApplet mApplet = null;
    
    
    private boolean mNoSuchElement = false;

    private String ACCESS_CONTROL_ENFORCER_TAG = "ACE ARA";

    public static final byte[] ARA_M_AID = new byte[] {
            (byte)0xA0, (byte)0x00, (byte)0x00, (byte)0x01, (byte)0x51, (byte)0x41, (byte)0x43, (byte)0x4C,
            (byte)0x00
    };

    public AraController(AccessControlEnforcer master ) {
    	mMaster = master;
    	mAccessRuleCache = mMaster.getAccessRuleCache();
    	mTerminal = mMaster.getTerminal();

    }

    public boolean isNoSuchElement(){
    	return mNoSuchElement;
    }
    
    public static byte[] getAraMAid() {
        return ARA_M_AID;
    }
    
	public synchronized boolean initialize(
			boolean loadAtStartup,
			ISmartcardServiceCallback callback) 
	{

		IChannel channel = null;
		try {
			 channel = this.handleOpenChannel(callback);
		} catch( MissingResourceException e ){
			channel = null;
		}
		
        if( channel == null ){
        	throw new AccessControlException("could not open channel");
        }

        try {
            // set new applet handler since a new channel is used.
        	mApplet = new AccessRuleApplet(channel);
        	byte[] tag = mApplet.readRefreshTag();
        	// if refresh tag is equal to the previous one it is not
        	// neccessary to read all rules again.
        	if( mAccessRuleCache.isRefreshTagEqual(tag)) {
        		SmartcardService.log(ACCESS_CONTROL_ENFORCER_TAG + SmartcardService._TAG + SmartcardService.LOG_LEVEL_MIDDLE, "Refresh tag has not changed. Using access rules from cache.");
        		return false;
        	}
        	SmartcardService.log(ACCESS_CONTROL_ENFORCER_TAG + SmartcardService._TAG  + SmartcardService.LOG_LEVEL_MIDDLE, "Refresh tag has changed.");
        	// set new refresh tag and empty cache.
        	mAccessRuleCache.setRefreshTag(tag);
        	mAccessRuleCache.clearCache();
        	
        	if( loadAtStartup ) {
	            // Read content from ARA 
        		SmartcardService.log(ACCESS_CONTROL_ENFORCER_TAG + SmartcardService._TAG + SmartcardService.LOG_LEVEL_MIDDLE, "Read ARs from ARA");
	        	this.readAllAccessRules();
        	}
        } catch (Exception e) {
        	SmartcardService.log(ACCESS_CONTROL_ENFORCER_TAG + SmartcardService._TAG + SmartcardService.LOG_LEVEL_BOTTOM, "ARA error: " + e.getLocalizedMessage());
            throw new AccessControlException(e.getLocalizedMessage()); // Throw Exception
        } finally { 
        	if( channel != null )
        		closeChannel(channel);
        }
        return true;
	}
	
	private IChannel handleOpenChannel( ISmartcardServiceCallback callback ){
        IChannel channel = null;
    	String reason = "";
		
        try {
            channel = openChannel(mTerminal, getAraMAid(), callback);
        } catch (Exception e) {
            String msg = e.toString();
            msg = " ARA-M couldn't be selected: " + msg;
            SmartcardService.log(ACCESS_CONTROL_ENFORCER_TAG + SmartcardService._TAG + SmartcardService.LOG_LEVEL_BOTTOM, msg);
            if (e instanceof NoSuchElementException) { 
            	mNoSuchElement = true;
                // SELECT failed
                // Access Rule Applet is not available => deny any access
            	reason = " No Access because ARA-M is not available";
            	SmartcardService.log(ACCESS_CONTROL_ENFORCER_TAG + SmartcardService._TAG + SmartcardService.LOG_LEVEL_BOTTOM, reason );
                throw new AccessControlException(reason);
            } else if( e instanceof MissingResourceException ){ 
            	// re-throw exception
            	// fixes issue 23
            	// this indicates that no channel is left for accessing the SE element
            	SmartcardService.log(ACCESS_CONTROL_ENFORCER_TAG + SmartcardService._TAG + SmartcardService.LOG_LEVEL_BOTTOM, "no channels left to access ARA-M: " + e.getMessage() );
            	throw (MissingResourceException)e;
        	} else { 
                // MANAGE CHANNEL failed or general error
        		// In order to be compliant with any UICC/SIM card on the market
        		// we are going to ignore the error and says that the ARA-M is not available.
        		// This not fully compliant with GP spec by required for mass compatibility.        		
            	mNoSuchElement = true; 
            	
            	reason = msg;
            	SmartcardService.log(ACCESS_CONTROL_ENFORCER_TAG + SmartcardService._TAG + SmartcardService.LOG_LEVEL_BOTTOM," ARA-M can not be accessed: " + msg);
                throw new AccessControlException(reason);
            }
        }   // End of Exception handling
        return channel;
	}


    /**
     * 
     * @return true if rules are read, false if not necessary or not available, but no error
     * @throws AccessControlException
     * @throws CardException
     */
    private boolean readAllAccessRules() throws AccessControlException, CardException {
    	
    	try {
			byte[] data = mApplet.readAllAccessRules();
			// no data returned, but no exception
			// -> no rule.
			if( data == null ) {
				return false;
			}
			
			BerTlv tlv = Response_DO_Factory.createDO( data );
			if( tlv == null ) {
				throw new AccessControlException("No valid data object found" );
			} if( tlv instanceof Response_ALL_AR_DO ){
				
				ArrayList<REF_AR_DO> array = ((Response_ALL_AR_DO)tlv).getRefArDos();
				if( array == null || array.size() == 0 ){
					return false; // no rules
				} else {
					Iterator<REF_AR_DO> iter = array.iterator();
					while( iter.hasNext() ){
						REF_AR_DO ref_ar_do = iter.next();
						this.mAccessRuleCache.putWithMerge(ref_ar_do.getRefDo(), ref_ar_do.getArDo());
					}
				}
			} else {
				throw new AccessControlException( "Applet returned invalid or wrong data object!");
			}
		} catch (ParserException e) {
			throw new AccessControlException("Parsing Data Object Exception: " + e.getMessage());
		}
    	return true;
    }
    
    private IChannel openChannel(ITerminal terminal, byte[] aid, ISmartcardServiceCallback callback) throws Exception
    {


        IChannel channel = terminal.openLogicalChannel(null, aid, callback);

        // set access conditions to access ARA-M.
        ChannelAccess araChannelAccess = new ChannelAccess();
        araChannelAccess.setAccess(ChannelAccess.ACCESS.ALLOWED, ACCESS_CONTROL_ENFORCER_TAG);
        araChannelAccess.setApduAccess(ChannelAccess.ACCESS.ALLOWED);
        channel.setChannelAccess(araChannelAccess);

        return channel;
}

    private void closeChannel(IChannel channel) {
        try {
            if (channel != null && channel.getChannelNumber() != 0) {

                channel.close();

            }
        } catch (CardException e) {
        }
    }
}
