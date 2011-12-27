package com.matburt.mobileorg.Gui;

import java.io.BufferedReader;
import java.io.StringReader;

import android.app.ListActivity;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ListView;

import com.markupartist.android.widget.ActionBar;
import com.markupartist.android.widget.ActionBar.Action;
import com.markupartist.android.widget.ActionBar.IntentAction;
import com.matburt.mobileorg.R;
import com.matburt.mobileorg.Parsing.MobileOrgApplication;
import com.matburt.mobileorg.Parsing.Node;
import com.matburt.mobileorg.Parsing.NodeEncryption;
import com.matburt.mobileorg.Parsing.OrgFile;
import com.matburt.mobileorg.Parsing.OrgFileParser;
import com.matburt.mobileorg.Services.SyncService;
import com.matburt.mobileorg.Settings.SettingsActivity;
import com.matburt.mobileorg.Settings.WizardActivity;
import com.matburt.mobileorg.Synchronizers.Synchronizer;

public class OutlineActivity extends ListActivity
{
	private static final int RESULT_DONTPOP = 1337;
	
	private static final int RUNFOR_EXPAND = 1;
	private static final int RUNFOR_EDITNODE = 2;
	private static final int RUNFOR_NEWNODE = 3;
	private static final int RUNFOR_VIEWNODE = 4;

	private MobileOrgApplication appInst;

	/**
	 * Keeps track of the depth of the tree. This is used to recursively finish
	 * OutlineActivities, updating the display properly on changes to the
	 * underlying data structure.
	 */
	private int depth;
	
	/**
	 * Keeps track of the last selected item chosen from the outline. When the
	 * outline resumes it will remember what node was selected. Purely cosmetic
	 * feature.
	 */
	private int lastSelection = 0;
	
	private ProgressDialog syncDialog;
	private OutlineListAdapter outlineAdapter;
	private SynchServiceReceiver syncReceiver;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		this.requestWindowFeature(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.outline);
		
		ActionBar actionBar = (ActionBar) findViewById(R.id.actionbar);
		actionBar.setTitle("MobileOrg");
		
		actionBar.setHomeAction(new IntentAction(this, new Intent(this,
				OutlineActivity.class), R.drawable.icon));
        
		Intent intent2 = new Intent(this, NodeEditActivity.class);
		intent2.putExtra("actionMode", NodeEditActivity.ACTIONMODE_CREATE);
        final Action otherAction = new IntentAction(this, intent2, R.drawable.ic_menu_compose);
        actionBar.addAction(otherAction);
        
		this.appInst = (MobileOrgApplication) this.getApplication();
		
		this.outlineAdapter = new OutlineListAdapter(this, appInst.nodestackTop());
		this.setListAdapter(outlineAdapter);
		
		Intent intent = getIntent();
		this.depth = intent.getIntExtra("depth", 1);
		
		if(this.depth == 1) {
			if(this.appInst.getOrgFiles().isEmpty())
                this.showWizard();
		}

		registerForContextMenu(getListView());
		
        this.syncReceiver = new SynchServiceReceiver();
	}
	
	@Override
	public void onResume() {	
		refreshDisplay();

		IntentFilter serviceFilter = new IntentFilter(SyncService.SYNC_UPDATE);
        registerReceiver(syncReceiver, serviceFilter);

        super.onResume();
	}
	
	@Override
	public void onPause() {
		unregisterReceiver(this.syncReceiver);
        super.onPause();
	}
		
	/**
	 * Refreshes the outline display. Should be called when the underlying 
	 * data has been updated.
	 */
	private void refreshDisplay() {
		// If this is the case, the parser/syncer has invalidated nodes
		if (this.depth != 1 && this.depth > this.appInst.nodestackSize()) {
			this.setResult(RESULT_DONTPOP);
			finish();
		}

		outlineAdapter.notifyDataSetChanged();
		getListView().setSelection(lastSelection);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
	    inflater.inflate(R.menu.outline_menu, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menu_sync:
			runSync();
			return true;
		
		case R.id.menu_settings:
			return runShowSettings();
		
		case R.id.menu_outline:
			appInst.clearNodestack();
			onResume();
			return true;
		
		case R.id.menu_capture:
			return runEditNewNodeActivity();
		}
		return false;
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View v,
			ContextMenuInfo menuInfo) {
		super.onCreateContextMenu(menu, v, menuInfo);
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.outline_contextmenu, menu);

		// Prevents editing of file nodes.
		if (this.depth == 1) {
			menu.findItem(R.id.contextmenu_edit).setVisible(false);
		} else {
			menu.findItem(R.id.contextmenu_delete).setVisible(false);
		}
	}
	
	@Override
	public boolean onContextItemSelected(MenuItem item) {
		AdapterContextMenuInfo info = (AdapterContextMenuInfo) item
				.getMenuInfo();
		Node node = (Node) getListAdapter().getItem(info.position);
		appInst.makeSureNodeIsParsed(node);

		switch (item.getItemId()) {
		case R.id.contextmenu_view:
			runViewNodeActivity(node);
			break;

		case R.id.contextmenu_edit:
			runEditNodeActivity(node);
			break;
			
		case R.id.contextmenu_delete:
			runDeleteNode(node);
			break;
		}

		return false;
	}

	@Override
	public void onListItemClick(ListView l, View v, int position, long id) {
		Node node = (Node) l.getItemAtPosition(position);

		if(node.encrypted) {
			runDecryptAndExpandNode(node);
			return;
		}
		else
			appInst.makeSureNodeIsParsed(node);

		this.lastSelection = position;
		
		if (node.hasChildren())
			runExpandSelection(node);
		else
			runViewNodeActivity(node);
	}

    private void showWizard() {
        startActivity(new Intent(this, WizardActivity.class));
    }
    
    private void runSync() {
		this.syncDialog = new ProgressDialog(this);
		this.syncDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
		this.syncDialog.setCancelable(false);
		this.syncDialog.setMessage("Started synchronization");
		this.syncDialog.show();

		startService(new Intent(this, SyncService.class));
    }
	
	private boolean runEditNewNodeActivity() {
		Intent intent = new Intent(this, NodeEditActivity.class);
		intent.putExtra("actionMode", NodeEditActivity.ACTIONMODE_CREATE);
		startActivityForResult(intent, RUNFOR_NEWNODE);
		return true;
	}
	
	private void runEditNodeActivity(Node node) {
		/* Pushes the given Node to the nodestack, to give it as argument to
		 * NodeEditActivity, which pops the node after use. We probably want to
		 * find a more elegant solution. */
		this.appInst.pushNodestack(node);
		
		Intent intent = new Intent(this,
				NodeEditActivity.class);
		intent.putExtra("actionMode", NodeEditActivity.ACTIONMODE_EDIT);
		startActivityForResult(intent, RUNFOR_EDITNODE);
	}

	private void runViewNodeActivity(Node node) {
		this.appInst.pushNodestack(node);
		
		Intent intent = new Intent(this, NodeViewActivity.class);
		startActivityForResult(intent, RUNFOR_VIEWNODE);
	}

	private void runExpandSelection(Node node) {
		appInst.pushNodestack(node);

		Intent intent = new Intent(this, OutlineActivity.class);
		int childDepth = this.depth + 1;
		intent.putExtra("depth", childDepth);
		startActivityForResult(intent, RUNFOR_EXPAND);
	}
	
	private void runDeleteNode(Node node) {
		// TODO Maybe prompt with a yes-no dialog
		appInst.removeFile(node.name);
		refreshDisplay();
	}
	
	private boolean runShowSettings() {
		Intent intent = new Intent(this, SettingsActivity.class);
		startActivity(intent);
		return true;
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent intent) {

		switch (requestCode) {

		case RUNFOR_EXPAND:
			if(resultCode != RESULT_DONTPOP)
				this.appInst.popNodestack();
			break;

		case RUNFOR_EDITNODE:
			this.appInst.popNodestack();
			break;
			
		case RUNFOR_NEWNODE:
			if(resultCode == RESULT_OK)
				this.refreshDisplay();
			break;
			
		case RUNFOR_VIEWNODE:
			this.appInst.popNodestack();
			break;

		case NodeEncryption.DECRYPT_MESSAGE:
			if (resultCode != RESULT_OK || intent == null)
				return;
			
			Node node = this.appInst.nodestackTop();
			this.appInst.popNodestack();
			parseEncryptedNode(intent, node);
			this.runExpandSelection(node);
			break;
		}
	}


	private void showMessage(String message) {
		this.syncDialog.setMessage(message);
	}
	
	private void stopSyncDialog() {
		this.syncDialog.dismiss();
		this.refreshDisplay();
	}
	
	private void showProgressDialog(int number, int total) {
		int progress = ((40 / total) * number);
		// TODO Fix progress bar
		this.syncDialog.setProgress(60 + progress + 1);
	}
	
	private void showProgress(int total) {
		this.syncDialog.setProgress(total);
	}
	
	// TODO Add the handling of thrown error messages from synchronizer
	public class SynchServiceReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			int num = intent.getIntExtra(Synchronizer.SYNC_FILES, 0);
			int totalfiles = intent.getIntExtra(Synchronizer.SYNC_FILES_TOTAL, 0);
			int progress = intent.getIntExtra(Synchronizer.SYNC_PROGRESS, 0);
			String message = intent.getStringExtra(Synchronizer.SYNC_MESSAGE);

			if (intent.getBooleanExtra(Synchronizer.SYNC_DONE, false))
				stopSyncDialog();
			if (num > 0)
				showProgressDialog(num, totalfiles);
			if (message != null)
				showMessage(message);
			if (progress > 0)
				showProgress(progress);
		}
	}
	
	/**
	 * This calls startActivityForResult() with Encryption.DECRYPT_MESSAGE. The
	 * result is handled by onActivityResult() in this class, which calls a
	 * function to parse the resulting plain text file.
	 */
	private void runDecryptAndExpandNode(Node node) {
		// if suitable APG version is installed
		if (NodeEncryption.isAvailable((Context) this)) {
			// retrieve the encrypted file data
			OrgFile orgfile = new OrgFile(node.name, getBaseContext());
			byte[] rawData = orgfile.getRawFileData();
			// save node so parsing function knows which node to parse into.
			appInst.pushNodestack(node);
			// and send it to APG for decryption
			NodeEncryption.decrypt(this, rawData);
		}
	}

	/**
	 * This function is called with the results of
	 * {@link #runDecryptAndExpandNode}.
	 */
	private void parseEncryptedNode(Intent data, Node node) {
		OrgFileParser ofp = new OrgFileParser(getBaseContext(), appInst);

		String decryptedData = data
				.getStringExtra(NodeEncryption.EXTRA_DECRYPTED_MESSAGE);

		ofp.parse(node, new BufferedReader(new StringReader(decryptedData)));
	}
}
