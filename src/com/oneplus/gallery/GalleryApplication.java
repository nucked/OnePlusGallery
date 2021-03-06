package com.oneplus.gallery;

import com.oneplus.base.HandlerObject;
import com.oneplus.base.Log;
import com.oneplus.gallery.media.MediaManager;

import android.app.Application;
import android.os.Handler;

/**
 * Application.
 */
public final class GalleryApplication extends Application implements HandlerObject
{
	// Constants.
	private static final String TAG = "OPGalleryApplication";
	
	
	// Fields.
	private static volatile GalleryApplication m_Current;
	private volatile Handler m_Handler;
	private volatile Thread m_MainThread;
	
	
	/**
	 * Get instance.
	 * @return Application instance.
	 */
	public static GalleryApplication current()
	{
		if(m_Current != null)
			return m_Current;
		synchronized(GalleryApplication.class)
		{
			if(m_Current == null)
			{
				Log.v(TAG, "current() - Wait for instance [start]");
				try
				{
					GalleryApplication.class.wait();
				}
				catch(InterruptedException ex)
				{
					Log.e(TAG, "current() - Interrupted", ex);
				}
				Log.v(TAG, "current() - Wait for instance [end]");
			}
		}
		return m_Current;
	}
	
	
	// Get handler.
	@Override
	public Handler getHandler()
	{
		return m_Handler;
	}
	
	
	// Check current thread.
	@Override
	public boolean isDependencyThread()
	{
		return (Thread.currentThread() == m_MainThread);
	}
	
	
	// Called when launching application.
	@Override
	public void onCreate()
	{
		// call super
		super.onCreate();
		
		Log.v(TAG, "onCreate()");
		
		// create handler
		m_MainThread = Thread.currentThread();
		m_Handler = new Handler();
		
		// save instance
		synchronized(GalleryApplication.class)
		{
			m_Current = this;
			GalleryApplication.class.notifyAll();
		}
		
		// initialize static components
		MediaManager.initialize();
	}
}
