package com.orangelabs.rcs.ri.intents;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

import com.orangelabs.rcs.ri.R;
import com.orangelabs.rcs.ri.utils.Utils;

/**
 * Call each Intents
 * 
 * @author Jean-Marc AUFFRET
 */
public class IntentApps extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Set layout
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.intents_apps);
        
        // Set title
        setTitle(R.string.menu_apps);
    
		// Set button callback
        Button btn = (Button)findViewById(R.id.load_chat);
        btn.setOnClickListener(btnListener);
        btn = (Button)findViewById(R.id.load_settings);
        btn.setOnClickListener(btnListener);
        btn = (Button)findViewById(R.id.load_ft);
        btn.setOnClickListener(btnListener);
        btn = (Button)findViewById(R.id.load_group_chat);
        btn.setOnClickListener(btnListener);
        btn = (Button)findViewById(R.id.initiate_ft);
        btn.setOnClickListener(btnListener);
        btn = (Button)findViewById(R.id.initiate_group_chat);
        btn.setOnClickListener(btnListener);
        btn = (Button)findViewById(R.id.initiate_chat);
        btn.setOnClickListener(btnListener);        
        btn = (Button)findViewById(R.id.load_ipcall);
        btn.setOnClickListener(btnListener);        
        btn = (Button)findViewById(R.id.initiate_ipcall);
        btn.setOnClickListener(btnListener);        
    }
    
    @Override
    public void onDestroy() {
    	super.onDestroy();
    }
    
    /**
     * Button callback
     */
    private OnClickListener btnListener = new OnClickListener() {
        public void onClick(View v) {
        	if (v.getId() == R.id.load_settings) {
        		try {
	        		Intent intent = new Intent(org.gsma.joyn.Intents.Client.ACTION_VIEW_SETTINGS);
	        		startActivity(intent);
        		} catch(Exception e) {
    		    	e.printStackTrace();
    				Utils.showMessageAndExit(IntentApps.this, getString(R.string.label_intent_failed));
        		}
        	} else
        	if (v.getId() == R.id.load_chat) {
        		try {
	        		Intent intent = new Intent(org.gsma.joyn.Intents.Chat.ACTION_VIEW_CHAT);
	        		startActivity(intent);
	    		} catch(Exception e) {
			    	e.printStackTrace();
					Utils.showMessageAndExit(IntentApps.this, getString(R.string.label_intent_failed));
	    		}
        	} else
        	if (v.getId() == R.id.initiate_chat) {
        		try {
	        		Intent intent = new Intent(org.gsma.joyn.Intents.Chat.ACTION_INITIATE_CHAT);
	        		startActivity(intent);
	    		} catch(Exception e) {
			    	e.printStackTrace();
					Utils.showMessageAndExit(IntentApps.this, getString(R.string.label_intent_failed));
	    		}
        	} else
        	if (v.getId() == R.id.load_group_chat) {
        		try {
	        		Intent intent = new Intent(org.gsma.joyn.Intents.Chat.ACTION_VIEW_GROUP_CHAT);
	        		startActivity(intent);
	    		} catch(Exception e) {
			    	e.printStackTrace();
					Utils.showMessageAndExit(IntentApps.this, getString(R.string.label_intent_failed));
	    		}
        	} else
        	if (v.getId() == R.id.initiate_group_chat) {
        		try {
	        		Intent intent = new Intent(org.gsma.joyn.Intents.Chat.ACTION_INITIATE_GROUP_CHAT);
	        		startActivity(intent);
	    		} catch(Exception e) {
			    	e.printStackTrace();
					Utils.showMessageAndExit(IntentApps.this, getString(R.string.label_intent_failed));
	    		}
        	} else
        	if (v.getId() == R.id.load_ft) {
        		try {
	        		Intent intent = new Intent(org.gsma.joyn.Intents.FileTransfer.ACTION_VIEW_FT);
	        		startActivity(intent);
	    		} catch(Exception e) {
			    	e.printStackTrace();
					Utils.showMessageAndExit(IntentApps.this, getString(R.string.label_intent_failed));
	    		}
        	} else
        	if (v.getId() == R.id.initiate_ft) {
        		try {
	        		Intent intent = new Intent(org.gsma.joyn.Intents.FileTransfer.ACTION_INITIATE_FT);
	        		startActivity(intent);
	    		} catch(Exception e) {
			    	e.printStackTrace();
					Utils.showMessageAndExit(IntentApps.this, getString(R.string.label_intent_failed));
	    		}
        	} else
	    	if (v.getId() == R.id.load_ipcall) {
	    		try {
	        		Intent intent = new Intent(org.gsma.joyn.Intents.IPCall.ACTION_VIEW_IPCALL);
	        		startActivity(intent);
	    		} catch(Exception e) {
			    	e.printStackTrace();
					Utils.showMessageAndExit(IntentApps.this, getString(R.string.label_intent_failed));
	    		}
	    	} else
        	if (v.getId() == R.id.initiate_ipcall) {
        		try {
	        		Intent intent = new Intent(org.gsma.joyn.Intents.IPCall.ACTION_INITIATE_IPCALL);
	        		startActivity(intent);
	    		} catch(Exception e) {
			    	e.printStackTrace();
					Utils.showMessageAndExit(IntentApps.this, getString(R.string.label_intent_failed));
	    		}
	    	}
        }
    };
}
