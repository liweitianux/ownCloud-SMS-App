package fr.unix_experience.owncloud_sms.engine;

/*
 *  Copyright (c) 2014, Loic Blot <loic.blot@unix-experience.fr>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *  
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.util.Date;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import fr.unix_experience.owncloud_sms.enums.MailboxID;
import fr.unix_experience.owncloud_sms.providers.SmsDataProvider;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

public class SmsFetcher {
	public SmsFetcher(Context ct) {
		_context = ct;
		
		_existingInboxMessages = null;
		_existingSentMessages = null;
		_existingDraftsMessages = null;
	}
	
	public JSONArray getJSONMessages() {
		_jsonTempDatas = new JSONArray();
				
		getInboxMessages(true);
		getSentMessages(true);
		getDraftMessages(true);
		
		return _jsonTempDatas;
	}
	
	public JSONArray getInboxMessages(Boolean toTempBuffer) {
		return getMailboxMessages("content://sms/inbox", toTempBuffer, MailboxID.INBOX);
	}
	
	public JSONArray getSentMessages(Boolean toTempBuffer) {
		return getMailboxMessages("content://sms/sent", toTempBuffer, MailboxID.SENT);
	}
	
	public JSONArray getDraftMessages(Boolean toTempBuffer) {
		return getMailboxMessages("content://sms/drafts", toTempBuffer, MailboxID.DRAFTS);
	}
	
	private JSONArray getMailboxMessages(String _mb, Boolean toTempBuffer, MailboxID _mbID) {
		if (_context == null || _mb.length() == 0) {
			return null;
		}
		
		Date startDate = new Date();
		// Fetch Sent SMS Message from Built-in Content Provider
		
		//Cursor c = (new SmsDataProvider(_context)).query(_mb);
		
		String existingIDs = buildExistingMessagesString();
		
		Cursor c = (new SmsDataProvider(_context)).query(Uri.parse(_mb), 
				new String[] { "read", "date", "address", "seen", "body", "_id", "type", }, "_id NOT IN (" + existingIDs + ")", null, null);
		
		// We create a list of strings to store results
		JSONArray results = new JSONArray();
		
		
		// Reading mailbox
		if (c != null && c.getCount() > 0) {
			
			c.moveToFirst();
			do {
				JSONObject entry = new JSONObject();

				try {
					// Reading each mail element
					int msgId = -1;
					for(int idx=0;idx<c.getColumnCount();idx++) {
						String colName = c.getColumnName(idx);
						
						// Id column is must be an integer
						if (colName.equals(new String("_id")) ||
							colName.equals(new String("type"))) {
							entry.put(colName, c.getInt(idx));
							
							// bufferize Id for future use
							if (colName.equals(new String("_id"))) {
								msgId = c.getInt(idx);
							}
						}
						// Seen and read must be pseudo boolean
						else if (colName.equals(new String("read")) ||
								colName.equals(new String("seen"))) {
							entry.put(colName, c.getInt(idx) > 0 ? "true" : "false");
						}
						else {
							entry.put(colName, c.getString(idx));
						}
					}
					
					// Mailbox ID is required by server
					entry.put("mbox", _mbID.ordinal());
					Log.d(TAG,"" + msgId);
					
					/*
					 * Use the existing lists to verify if mail needs to be buffered
					 * It's useful to decrease data use
					 */
					if (_mbID == MailboxID.INBOX ||
						_mbID == MailboxID.SENT ||
						_mbID == MailboxID.DRAFTS) {
						if (toTempBuffer) {
							_jsonTempDatas.put(entry);
						}
						else {
							results.put(entry);
						}
					}
					
				} catch (JSONException e) {
					Log.e(TAG, "JSON Exception when reading SMS Mailbox", e);
					c.close();
				}
			}
			while(c.moveToNext());
			
			Log.d(TAG, c.getCount() + " messages read from " +_mb);
			
			c.close();
		}

		long diffInMs = (new Date()).getTime() - startDate.getTime();
		
		Log.d(TAG, "SmsFetcher->getMailboxMessages() Time spent: " + diffInMs);
		
		return results;
	}
	
	// Used by Content Observer
	public JSONArray getLastMessage(String _mb) {
		if (_context == null || _mb.length() == 0) {
			return null;
		}
		 
		// Fetch Sent SMS Message from Built-in Content Provider
		Cursor c = (new SmsDataProvider(_context)).query(_mb);
		
		c.moveToNext();
		
		// We create a list of strings to store results
		JSONArray results = new JSONArray();
		
		JSONObject entry = new JSONObject();

		try {
			for(int idx=0;idx<c.getColumnCount();idx++) {
				String colName = c.getColumnName(idx);
				
				// Id column is must be an integer
				if (colName.equals(new String("_id")) ||
					colName.equals(new String("type"))) {
					entry.put(colName, c.getInt(idx));
					
					// bufferize Id for future use
					if (colName.equals(new String("_id"))) {
					}
				}
				// Seen and read must be pseudo boolean
				else if (colName.equals(new String("read")) ||
						colName.equals(new String("seen"))) {
					entry.put(colName, c.getInt(idx) > 0 ? "true" : "false");
				}
				else {
					entry.put(colName, c.getString(idx));
				}
			}
			
			// Mailbox ID is required by server
			switch (entry.getInt("type")) {
				case 1: entry.put("mbox", MailboxID.INBOX.ordinal()); break;
				case 2: entry.put("mbox", MailboxID.SENT.ordinal()); break;
				case 3: entry.put("mbox", MailboxID.DRAFTS.ordinal()); break;
			}
			results.put(entry);
		} catch (JSONException e) {
			Log.e(TAG, "JSON Exception when reading SMS Mailbox", e);
			c.close();
		}
		
		c.close();
		
		return results;
	}
	
	private String buildExistingMessagesString() {
		StringBuilder sb = new StringBuilder();
		if (_existingInboxMessages != null) {
			int len = _existingInboxMessages.length(); 
	        for (int i = 0; i < len; i++) {
	        	try {
	        		if (sb.length() > 0) {
	        			sb.append(",");
	        		}
	        		sb.append(_existingInboxMessages.getInt(i));
				} catch (JSONException e) {
					
				}
	        }
		}
		if (_existingSentMessages != null) {
			int len = _existingSentMessages.length(); 
	        for (int i = 0; i < len; i++) {
	        	try {
	        		if (sb.length() > 0) {
	        			sb.append(",");
	        		}
	        		sb.append(_existingSentMessages.getInt(i));
				} catch (JSONException e) {
					
				}
	        }
		}
		
		if (_existingDraftsMessages != null) {
			int len = _existingDraftsMessages.length(); 
	        for (int i = 0; i < len; i++) {
	        	try {
	        		if (sb.length() > 0) {
	        			sb.append(",");
	        		}
	        		sb.append(_existingDraftsMessages.getInt(i));
				} catch (JSONException e) {
					
				}
	        }
		}
		
		return sb.toString();
	}
	
	public void setExistingInboxMessages(JSONArray inboxMessages) {
		_existingInboxMessages = inboxMessages;
	}

	public void setExistingSentMessages(JSONArray sentMessages) {
		_existingSentMessages = sentMessages;
	}

	public void setExistingDraftsMessages(JSONArray draftMessages) {
		_existingDraftsMessages = draftMessages;
	}
	
	private Context _context;
	private JSONArray _jsonTempDatas;
	private JSONArray _existingInboxMessages;
	private JSONArray _existingSentMessages;
	private JSONArray _existingDraftsMessages;
	
	private static final String TAG = SmsFetcher.class.getSimpleName();

	
}
