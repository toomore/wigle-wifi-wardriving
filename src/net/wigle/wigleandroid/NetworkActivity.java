package net.wigle.wigleandroid;

import java.text.SimpleDateFormat;
import java.util.Arrays;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.api.IMapController;
import org.osmdroid.api.IMapView;
import org.osmdroid.views.MapView;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.DialogFragment;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.text.ClipboardManager;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

@SuppressWarnings("deprecation")
public class NetworkActivity extends ActionBarActivity implements DialogListener {
  private static final int MENU_EXIT = 11;
  private static final int MENU_COPY = 12;
  private static final int NON_CRYPTO_DIALOG = 130;

  private static final int MSG_OBS_UPDATE = 1;
  private static final int MSG_OBS_DONE = 2;

  private Network network;
  private IMapView mapView;
  private SimpleDateFormat format;
  private int observations = 0;
  private final ConcurrentLinkedHashMap<LatLon, Integer> obsMap = new ConcurrentLinkedHashMap<LatLon, Integer>( 512 );

  // used for shutting extraneous activities down on an error
  public static NetworkActivity networkActivity;

  /** Called when the activity is first created. */
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    final ActionBar actionBar = getSupportActionBar();
    actionBar.setDisplayHomeAsUpEnabled(true);

    // set language
    MainActivity.setLocale( this );
    setContentView(R.layout.network);
    networkActivity = this;

    final Intent intent = getIntent();
    final String bssid = intent.getStringExtra( ListFragment.NETWORK_EXTRA_BSSID );
    MainActivity.info( "bssid: " + bssid );

    network = MainActivity.getNetworkCache().get(bssid);
    format = NetworkListAdapter.getConstructionTimeFormater( this );

    TextView tv = (TextView) findViewById( R.id.bssid );
    tv.setText( bssid );

    if ( network == null ) {
      MainActivity.info( "no network found in cache for bssid: " + bssid );
    }
    else {
      // do gui work
      tv = (TextView) findViewById( R.id.ssid );
      tv.setText( network.getSsid() );

      final int image = NetworkListAdapter.getImage( network );
      final ImageView ico = (ImageView) findViewById( R.id.wepicon );
      ico.setImageResource( image );
      final ImageView ico2 = (ImageView) findViewById( R.id.wepicon2 );
      ico2.setImageResource( image );

      tv = (TextView) findViewById( R.id.na_signal );
      final int level = network.getLevel();
      tv.setTextColor( NetworkListAdapter.getSignalColor( level ) );
      tv.setText( Integer.toString( level ) );

      tv = (TextView) findViewById( R.id.na_type );
      tv.setText( network.getType().name() );

      tv = (TextView) findViewById( R.id.na_firsttime );
      tv.setText( NetworkListAdapter.getConstructionTime( format, network ) );

      tv = (TextView) findViewById( R.id.na_chan );
      if ( ! NetworkType.WIFI.equals(network.getType()) ) {
        tv.setText( getString(R.string.na) );
      }
      else {
        Integer chan = network.getChannel();
        chan = chan != null ? chan : network.getFrequency();
        tv.setText( " " + Integer.toString(chan) + " " );
      }

      tv = (TextView) findViewById( R.id.na_cap );
      tv.setText( " " + network.getCapabilities().replace("][", "]\n[") );

      setupMap( network );
      // kick off the query now that we have our map
      setupQuery();
      setupButton( network );
    }
  }

  @Override
  public void onDestroy() {
    networkActivity = null;
    super.onDestroy();
  }

  @SuppressLint("HandlerLeak")
  private void setupQuery() {
    // what runs on the gui thread
    final Handler handler = new Handler() {
      @Override
      public void handleMessage( final Message msg ) {
        final TextView tv = (TextView) findViewById( R.id.na_observe );
        if ( msg.what == MSG_OBS_UPDATE ) {
          tv.setText( " " + Integer.toString( observations ) + "...");
        }
        else if ( msg.what == MSG_OBS_DONE ) {
          tv.setText( " " + Integer.toString( observations ) );
        }
      }
    };

    final String sql = "SELECT level,lat,lon FROM "
      + DatabaseHelper.LOCATION_TABLE + " WHERE bssid = '" + network.getBssid() + "'";

    final QueryThread.Request request = new QueryThread.Request( sql, new QueryThread.ResultHandler() {
      @Override
      public void handleRow( final Cursor cursor ) {
        observations++;
        obsMap.put( new LatLon( cursor.getFloat(1), cursor.getFloat(2) ), cursor.getInt(0) );
        if ( ( observations % 10 ) == 0 ) {
          // change things on the gui thread
          handler.sendEmptyMessage( MSG_OBS_UPDATE );
        }
      }

      @Override
      public void complete() {
        handler.sendEmptyMessage( MSG_OBS_DONE );
        if ( mapView != null ) {
          // force a redraw
          ((View) mapView).postInvalidate();
        }
      }
    });
    ListFragment.lameStatic.dbHelper.addToQueue( request );
  }

  private void setupMap( final Network network ) {
    final IGeoPoint point = MappingFragment.getCenter( this, network.getGeoPoint(), null );
    mapView = new MapView( this, 256 );
    final OpenStreetMapViewWrapper overlay = setupMap( this, point, mapView, R.id.netmap_rl );
    if ( overlay != null ) {
      overlay.setSingleNetwork( network );
      overlay.setObsMap( obsMap );
    }
  }

  public static OpenStreetMapViewWrapper setupMap( final Activity activity, final IGeoPoint center,
      final IMapView mapView, final int id ) {

    OpenStreetMapViewWrapper overlay = null;
    if ( center != null ) {
      // view
      final RelativeLayout rlView = (RelativeLayout) activity.findViewById( id );

      if ( mapView instanceof View ) {
        ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
          LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT);
        ((View) mapView).setLayoutParams(params);
      }

      if ( mapView instanceof MapView ) {
        final MapView osmMapView = (MapView) mapView;
        rlView.addView( osmMapView );
        osmMapView.setBuiltInZoomControls( true );
        osmMapView.setMultiTouchControls( true );

        overlay = new OpenStreetMapViewWrapper( activity );
        osmMapView.getOverlays().add( overlay );
      }

      final IMapController mapControl = mapView.getController();
      mapControl.setCenter( center );
      mapControl.setZoom( 18 );
      mapControl.setCenter( center );
    }

    return overlay;
  }

  private void setupButton( final Network network ) {
    final Button connectButton = (Button) findViewById( R.id.connect_button );
    if ( ! NetworkType.WIFI.equals(network.getType()) ) {
      connectButton.setEnabled( false );
    }

    connectButton.setOnClickListener( new OnClickListener() {
      @Override
      public void onClick( final View buttonView ) {
        if ( Network.CRYPTO_NONE == network.getCrypto() ) {
          MainActivity.createConfirmation( NetworkActivity.this, "You have permission to access this network?",
              0, NON_CRYPTO_DIALOG);
        }
        else {
          final CryptoDialog cryptoDialog = CryptoDialog.newInstance(network);
          cryptoDialog.show(NetworkActivity.this.getSupportFragmentManager(), "crypto-dialog");
        }
      }
    });
  }

  private int getExistingSsid( final String ssid ) {
    final WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
    final String quotedSsid = "\"" + ssid + "\"";
    int netId = -2;

    for ( final WifiConfiguration config : wifiManager.getConfiguredNetworks() ) {
      MainActivity.info( "bssid: " + config.BSSID
          + " ssid: " + config.SSID
          + " status: " + config.status
          + " id: " + config.networkId
          + " preSharedKey: " + config.preSharedKey
          + " priority: " + config.priority
          + " wepTxKeyIndex: " + config.wepTxKeyIndex
          + " allowedAuthAlgorithms: " + config.allowedAuthAlgorithms
          + " allowedGroupCiphers: " + config.allowedGroupCiphers
          + " allowedKeyManagement: " + config.allowedKeyManagement
          + " allowedPairwiseCiphers: " + config.allowedPairwiseCiphers
          + " allowedProtocols: " + config.allowedProtocols
          + " hiddenSSID: " + config.hiddenSSID
          + " wepKeys: " + Arrays.toString( config.wepKeys )
          );
      if ( quotedSsid.equals( config.SSID ) ) {
        netId = config.networkId;
        break;
      }
    }

    return netId;
  }

  @Override
  public void handleDialog(final int dialogId) {
    switch(dialogId) {
      case NON_CRYPTO_DIALOG:
        connectToNetwork( null );
        break;
      default:
        MainActivity.warn("Network unhandled dialogId: " + dialogId);
    }
  }

  private void connectToNetwork( final String password ) {
    final int preExistingNetId = getExistingSsid( network.getSsid() );
    final WifiManager wifiManager = (WifiManager) getSystemService( Context.WIFI_SERVICE );
    int netId = -2;
    if ( preExistingNetId < 0 ) {
      final WifiConfiguration newConfig = new WifiConfiguration();
      newConfig.SSID = "\"" + network.getSsid() + "\"";
      newConfig.hiddenSSID = false;
      if ( password != null ) {
        if ( Network.CRYPTO_WEP == network.getCrypto() ) {
          newConfig.wepKeys = new String[]{ "\"" + password + "\"" };
        }
        else {
          newConfig.preSharedKey = "\"" + password + "\"";
        }
      }

      netId = wifiManager.addNetwork( newConfig );
    }

    if ( netId >= 0 ) {
      final boolean disableOthers = true;
      wifiManager.enableNetwork(netId, disableOthers);
    }
  }

  public static class CryptoDialog extends DialogFragment {
    public static CryptoDialog newInstance(final Network network) {
      final CryptoDialog frag = new CryptoDialog();
      Bundle args = new Bundle();
      args.putString("ssid", network.getSsid());
      args.putString("capabilities", network.getCapabilities());
      args.putString("level", Integer.toString(network.getLevel()));
      frag.setArguments(args);
      return frag;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
        Bundle savedInstanceState) {

      final Dialog dialog = getDialog();
      View view = inflater.inflate(R.layout.cryptodialog, container);
      dialog.setTitle(getArguments().getString("ssid"));

      TextView text = (TextView) view.findViewById( R.id.security );
      text.setText(getArguments().getString("capabilities"));

      text = (TextView) view.findViewById( R.id.signal );
      text.setText(getArguments().getString("level"));

      final Button ok = (Button) view.findViewById( R.id.ok_button );

      final EditText password = (EditText) view.findViewById( R.id.edit_password );
      password.addTextChangedListener( new SettingsActivity.SetWatcher() {
        @Override
        public void onTextChanged( final String s ) {
          if ( s.length() > 0 ) {
            ok.setEnabled(true);
          }
        }
      });

      final CheckBox showpass = (CheckBox) view.findViewById( R.id.showpass );
      showpass.setOnCheckedChangeListener(new OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged( final CompoundButton buttonView, final boolean isChecked ) {
          if ( isChecked ) {
            password.setInputType( InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD );
            password.setTransformationMethod( null );
          }
          else {
            password.setInputType( InputType.TYPE_TEXT_VARIATION_PASSWORD );
            password.setTransformationMethod(
              android.text.method.PasswordTransformationMethod.getInstance() );
          }
        }
      });

      ok.setOnClickListener( new OnClickListener() {
          @Override
          public void onClick( final View buttonView ) {
            try {
              final NetworkActivity networkActivity = (NetworkActivity) getActivity();
              networkActivity.connectToNetwork( password.getText().toString() );
              dialog.dismiss();
            }
            catch ( Exception ex ) {
              // guess it wasn't there anyways
              MainActivity.info( "exception dismissing crypto dialog: " + ex );
            }
          }
        } );

      Button cancel = (Button) view.findViewById( R.id.cancel_button );
      cancel.setOnClickListener( new OnClickListener() {
          @Override
          public void onClick( final View buttonView ) {
            try {
              dialog.dismiss();
            }
            catch ( Exception ex ) {
              // guess it wasn't there anyways
              MainActivity.info( "exception dismissing crypto dialog: " + ex );
            }
          }
        } );

      return view;
    }
  }

  /* Creates the menu items */
  @Override
  public boolean onCreateOptionsMenu( final Menu menu ) {
      MenuItem item = menu.add(0, MENU_COPY, 0, getString(R.string.menu_copy_network));
      item.setIcon( android.R.drawable.ic_menu_save );

      item = menu.add(0, MENU_EXIT, 0, getString(R.string.menu_return));
      item.setIcon( android.R.drawable.ic_menu_revert );

      return true;
  }

  /* Handles item selections */
  @Override
  public boolean onOptionsItemSelected( final MenuItem item ) {
      switch ( item.getItemId() ) {
        case MENU_EXIT:
          // call over to finish
          finish();
          return true;
        case MENU_COPY:
          // copy the netid
          if (network != null) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setText(network.getBssid());
          }
          return true;
      }
      return false;
  }
}
