package sh.siava.AOSPMods.modpacks;

import static android.content.Context.CONTEXT_IGNORE_SECURITY;
import static de.robv.android.xposed.XposedBridge.log;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClassIfExists;
import static sh.siava.AOSPMods.BuildConfig.APPLICATION_ID;
import static sh.siava.AOSPMods.modpacks.utils.BootLoopProtector.isBootLooped;
import static sh.siava.AOSPMods.modpacks.Constants.SYSTEM_UI_PACKAGE;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.Arrays;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.AOSPMods.BuildConfig;
import sh.siava.AOSPMods.IRootProviderProxy;
import sh.siava.AOSPMods.R;
import sh.siava.AOSPMods.modpacks.utils.SystemUtils;

@SuppressWarnings("RedundantThrows")
public class XPLauncher {

	public static boolean isChildProcess = false;
	public static String processName = "";

	public static ArrayList<XposedModPack> runningMods = new ArrayList<>();
	public Context mContext = null;

	public static IRootProviderProxy rootProxyIPC;
	/** @noinspection FieldCanBeLocal*/
	private ServiceConnection mRootProxyConnection;

	public XPLauncher() {
	}

	public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
		try
		{
			isChildProcess = lpparam.processName.contains(":");
			processName = lpparam.processName;
		} catch (Throwable ignored)
		{
			isChildProcess = false;
		}

		//If example class isn't found, user is using an older version. Don't load the module at all
		if (Build.VERSION.SDK_INT ==  Build.VERSION_CODES.TIRAMISU && lpparam.packageName.equals(SYSTEM_UI_PACKAGE)) {
			Class<?> A33R18Example = findClassIfExists("com.android.systemui.shade.NotificationPanelViewController", lpparam.classLoader);
			if (A33R18Example == null) return;
		}

		/*if (lpparam.packageName.equals(SYSTEM_UI_PACKAGE) && DEBUG && false) {
			log("------------");
			Helpers.dumpClass("com.android.systemui.statusbar.notification.collection.NotifCollection", lpparam.classLoader);
			log("------------");
		}*/

		findAndHookMethod(Instrumentation.class, "newApplication", ClassLoader.class, String.class, Context.class, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				if (mContext == null)
				{
					mContext = (Context) param.args[2];

					XPrefs.init(mContext);

					ResourceManager.modRes = mContext.createPackageContext(APPLICATION_ID, CONTEXT_IGNORE_SECURITY)
							.getResources();

					if(Arrays.asList(ResourceManager.modRes.getStringArray(R.array.root_requirement)).contains(mContext.getPackageName()))
					{
						forceConnectRootService();
					}

					if(isBootLooped(mContext.getPackageName()))
					{
						log(String.format("AOSPMods: Possible bootloop in %s. Will not load for now", mContext.getPackageName()));
						return;
					}

					new SystemUtils(mContext);
					XPrefs.setPackagePrefs(mContext.getPackageName());
				}

				for (Class<? extends XposedModPack> mod : ModPacks.getMods(lpparam.packageName)) {
					try {
						XposedModPack instance = mod.getConstructor(Context.class).newInstance(mContext);
						if (!instance.listensTo(lpparam.packageName)) continue;
						try {
							instance.updatePrefs();
						} catch (Throwable ignored) {}
						instance.handleLoadPackage(lpparam);
						runningMods.add(instance);
					} catch (Throwable T) {
						log("Start Error Dump - Occurred in " + mod.getName());
						log(T);
					}
				}
			}
		});

	}
	private void connectRootService()
	{
		// Start RootService connection
		Intent intent = new Intent();
		mRootProxyConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			rootProxyIPC = IRootProviderProxy.Stub.asInterface(service);
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			rootProxyIPC = null;

			forceConnectRootService();
		}
	};
		intent.setComponent(new ComponentName(BuildConfig.APPLICATION_ID, BuildConfig.APPLICATION_ID + ".service.RootProviderProxy"));

		mContext.bindService(intent, mRootProxyConnection, Context.BIND_AUTO_CREATE);
	}

	private void forceConnectRootService() {
		new Thread(() -> {
			while (rootProxyIPC == null) {
				connectRootService();
				try {
					Thread.sleep(1000);
				} catch (Throwable ignored) {}
			}
		}).start();
	}

}