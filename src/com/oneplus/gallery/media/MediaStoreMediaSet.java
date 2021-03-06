package com.oneplus.gallery.media;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.provider.MediaStore.Files;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Video;

import com.oneplus.base.Handle;
import com.oneplus.base.HandleSet;
import com.oneplus.base.HandlerBaseObject;
import com.oneplus.base.HandlerUtils;
import com.oneplus.base.Log;

/**
 * Media set based-on media store.
 */
public abstract class MediaStoreMediaSet extends HandlerBaseObject implements MediaSet
{
	// Constants.
	private static final Uri CONTENT_URI_FILE = Files.getContentUri("external");
	private static final Uri CONTENT_URI_IMAGE = Images.Media.EXTERNAL_CONTENT_URI;
	private static final Uri CONTENT_URI_VIDEO = Video.Media.EXTERNAL_CONTENT_URI;
	private static final long DURATION_HANDLE_MS_CONTENT_CHANGE_DELAY = 1500;
	private static final int MSG_MEDIA_COUNT_CHANGED = -10000;
	private static final int MSG_HANDLE_MS_CONTENT_CHANGE = -10001;
	private static final int MSG_ADD_MEDIA_TO_MEDIA_LIST = -10010;
	private static final int MSG_REMOVE_MEDIA_FROM_MEDIA_LIST = -10011;
	
	
	// Fields.
	private List<MediaListImpl> m_ActiveMediaLists;
	private volatile Handle m_MediaCountRefreshHandle;
	private final MediaManager.ContentChangeCallback m_MediaStoreContentChangedCB = new MediaManager.ContentChangeCallback()
	{
		@Override
		public void onContentChanged(Uri contentUri)
		{
			onMediaStoreContentChanged(contentUri);
		}
	};
	private HandleSet m_MediaStoreContentChangedCBHandles;
	private final Type m_Type;
	private String m_QueryCondition;
	private String[] m_QueryConditionArgs;
	private final MediaManager.ContentProviderAccessCallback m_RefreshMediaCountCallback = new MediaManager.ContentProviderAccessCallback()
	{
		@Override
		public void onAccessContentProvider(ContentResolver contentResolver, Uri contentUri, ContentProviderClient client) throws RemoteException
		{
			int count = refreshMediaCount(contentResolver, contentUri, client);
			HandlerUtils.sendMessage(MediaStoreMediaSet.this, MSG_MEDIA_COUNT_CHANGED, count, 0, null);
		}
	};
	
	
	// Media list implementation.
	private final class MediaListImpl extends BasicMediaList
	{
		public MediaListImpl(MediaComparator comparator, int maxMediaCount)
		{
			super(comparator, maxMediaCount);
		}
		
		public void getAllContentUris(Set<Uri> result)
		{
			for(int i = this.size() - 1 ; i >= 0 ; --i)
				result.add(this.get(i).getContentUri());
		}
		
		@Override
		public void release()
		{
			super.release();
			this.clearMedia();
			onMediaListReleased(this);
		}
		
		public void removeMedia(Set<Uri> contentUris)
		{
			for(int i = this.size() - 1 ; i >= 0 ; --i)
			{
				Media media = this.get(i);
				if(contentUris.contains(media.getContentUri()))
					this.removeMedia(media);
			}
		}
	}
	
	
	/**
	 * Initialize new MediaStoreMediaSet instance.
	 * @param type Media set type.
	 */
	protected MediaStoreMediaSet(Type type)
	{
		super(true);
		if(type == null)
			throw new IllegalArgumentException("No type specified.");
		m_Type = type;
	}
	
	
	// Add media to media list.
	private void addMediaToMediaList(MediaListImpl mediaList, Media media)
	{
		if(!mediaList.get(MediaList.PROP_IS_RELEASED))
			mediaList.addMedia(media);
	}
	private void addMediaToMediaList(MediaListImpl mediaList, List<Media> media, boolean isSorted)
	{
		if(!mediaList.get(MediaList.PROP_IS_RELEASED))
			mediaList.addMedia(media, isSorted);
	}

	
	// Get media set type.
	@Override
	public Type getType()
	{
		return m_Type;
	}
	
	
	// Handle media store content change event.
	private void handleMediaStoreContentChange()
	{
		// check state
		if(this.get(PROP_IS_RELEASED))
			return;
		
		// refresh media count
		this.refreshMediaCount(false);
		
		// refresh media lists
		if(m_ActiveMediaLists != null)
		{
			for(int i = m_ActiveMediaLists.size() - 1 ; i >= 0 ; --i)
				this.refreshMediaList(m_ActiveMediaLists.get(i));
		}
	}
	
	
	// Handle message.
	@SuppressWarnings("unchecked")
	@Override
	protected void handleMessage(Message msg)
	{
		switch(msg.what)
		{
			case MSG_ADD_MEDIA_TO_MEDIA_LIST:
			{
				Object[] params = (Object[])msg.obj;
				if(params[1] instanceof Media)
					this.addMediaToMediaList((MediaListImpl)params[0], (Media)params[1]);
				else
					this.addMediaToMediaList((MediaListImpl)params[0], (List<Media>)params[1], msg.arg1 != 0);
				break;
			}
			
			case MSG_HANDLE_MS_CONTENT_CHANGE:
				this.handleMediaStoreContentChange();
				break;
			
			case MSG_MEDIA_COUNT_CHANGED:
				this.setReadOnly(PROP_MEDIA_COUNT, msg.arg1);
				break;
				
			case MSG_REMOVE_MEDIA_FROM_MEDIA_LIST:
			{
				Object[] params = (Object[])msg.obj;
				if(params[1] instanceof Media)
					this.removeMediaFromMediaList((MediaListImpl)params[0], (Media)params[1]);
				else if(params[1] instanceof Set<?>)
					this.removeMediaFromMediaList((MediaListImpl)params[0], (Set<Uri>)params[1]);
				break;
			}
				
			default:
				super.handleMessage(msg);
				break;
		}
	}
	
	
	// Called when media list released.
	private void onMediaListReleased(MediaListImpl mediaList)
	{
		if(m_ActiveMediaLists.remove(mediaList))
			Log.v(TAG, "onMediaListReleased() - Active media list count : ", m_ActiveMediaLists.size());
	}
	
	
	/**
	 * Called when content in media store has been changed.
	 * @param contentUri Content URI of changed content.
	 */
	protected void onMediaStoreContentChanged(Uri contentUri)
	{
		Handler handler = this.getHandler();
		if(!handler.hasMessages(MSG_HANDLE_MS_CONTENT_CHANGE))
			handler.sendEmptyMessageDelayed(MSG_HANDLE_MS_CONTENT_CHANGE, DURATION_HANDLE_MS_CONTENT_CHANGE_DELAY);
	}
	
	
	// Release media set.
	@Override
	protected void onRelease()
	{
		// release all media lists
		if(m_ActiveMediaLists != null && !m_ActiveMediaLists.isEmpty())
		{
			Log.v(TAG, "onRelease() - Release all media lists");
			for(int i = m_ActiveMediaLists.size() - 1 ; i >= 0 ; --i)
				m_ActiveMediaLists.get(i).release();
		}
		
		// cancel refresh
		m_MediaCountRefreshHandle = Handle.close(m_MediaCountRefreshHandle);
		
		// unregister content change call-back
		MediaManager.postToContentThread(new Runnable()
		{
			@Override
			public void run()
			{
				m_MediaStoreContentChangedCBHandles = Handle.close(m_MediaStoreContentChangedCBHandles);
			}
		});
		
		// call super
		super.onRelease();
	}
	

	// Open media list.
	@Override
	public MediaList openMediaList(final MediaComparator comparator, final int maxMediaCount, int flags)
	{
		// check state
		this.verifyAccess();
		
		// check parameter
		if(comparator == null)
			throw new IllegalArgumentException("No comparator.");
		
		// create media list
		final MediaListImpl mediaList = new MediaListImpl(comparator, maxMediaCount);
		if(m_ActiveMediaLists == null)
			m_ActiveMediaLists = new ArrayList<>();
		m_ActiveMediaLists.add(mediaList);
		Log.v(TAG, "openMediaList() - Active media list count : ", m_ActiveMediaLists.size());
		
		// start updating media list
		MediaManager.accessContentProvider(CONTENT_URI_FILE, new MediaManager.ContentProviderAccessCallback()
		{
			@Override
			public void onAccessContentProvider(ContentResolver contentResolver, Uri contentUri, ContentProviderClient client) throws RemoteException
			{
				String sortOrder = comparator.getContentProviderSortOrder();
				if(maxMediaCount >= 0)
					sortOrder += (" LIMIT " + maxMediaCount);
				Cursor cursor = client.query(contentUri, MediaStoreMedia.MEDIA_COLUMNS, m_QueryCondition, m_QueryConditionArgs, sortOrder);
				boolean isFirstMedia = true;
				List<Media> tempMediaList = null;
				Handler handler = getHandler();
				if(cursor != null)
				{
					try
					{
						while(cursor.moveToNext())
						{
							Media media = MediaStoreMedia.create(cursor, handler);
							if(media == null)
								continue;
							if(isFirstMedia)
							{
								isFirstMedia = false;
								HandlerUtils.sendMessage(MediaStoreMediaSet.this, MSG_ADD_MEDIA_TO_MEDIA_LIST, 1, 0, new Object[]{ mediaList, media });
							}
							else
							{
								if(tempMediaList == null)
									tempMediaList = new ArrayList<>();
								tempMediaList.add(media);
								if(tempMediaList.size() >= 64)
								{
									if(mediaList.get(MediaList.PROP_IS_RELEASED))
									{
										tempMediaList = null;
										break;
									}
									HandlerUtils.sendMessage(MediaStoreMediaSet.this, MSG_ADD_MEDIA_TO_MEDIA_LIST, 1, 0, new Object[]{ mediaList, tempMediaList });
									tempMediaList = null;
								}
							}
						}
						if(tempMediaList != null)
							HandlerUtils.sendMessage(MediaStoreMediaSet.this, MSG_ADD_MEDIA_TO_MEDIA_LIST, 1, 0, new Object[]{ mediaList, tempMediaList });
					}
					finally
					{
						cursor.close();
					}
				}
			}
		});
		
		// complete
		return mediaList;
	}
	
	
	/**
	 * Refresh media count.
	 * @param clearFirst True to clear media count first.
	 */
	protected void refreshMediaCount(boolean clearFirst)
	{
		// check state
		this.verifyAccess();
		if(this.get(PROP_IS_RELEASED))
			return;
		
		// clear media count
		if(clearFirst)
			this.setReadOnly(PROP_MEDIA_COUNT, null);
		
		// refresh
		Handle.close(m_MediaCountRefreshHandle);
		m_MediaCountRefreshHandle = MediaManager.accessContentProvider(CONTENT_URI_FILE, m_RefreshMediaCountCallback);
	}
	
	
	// Refresh media count.
	protected int refreshMediaCount(ContentResolver contentResolver, Uri contentUri, ContentProviderClient client) throws RemoteException
	{
		// register content change call-back
		if(!Handle.isValid(m_MediaStoreContentChangedCBHandles))
		{
			m_MediaStoreContentChangedCBHandles = new HandleSet();
			m_MediaStoreContentChangedCBHandles.addHandle(MediaManager.registerContentChangedCallback(CONTENT_URI_IMAGE, m_MediaStoreContentChangedCB));
			m_MediaStoreContentChangedCBHandles.addHandle(MediaManager.registerContentChangedCallback(CONTENT_URI_VIDEO, m_MediaStoreContentChangedCB));
		}
		
		// query media count
		Cursor cursor = client.query(contentUri, new String[]{ "Count(" + FileColumns._ID + ")" }, m_QueryCondition, m_QueryConditionArgs, null);
		if(cursor != null)
		{
			try
			{
				if(cursor.moveToNext())
					return cursor.getInt(0);
			}
			finally
			{
				cursor.close();
			}
		}
		return 0;
	}
	
	
	// Refresh media list.
	private void refreshMediaList(final MediaListImpl mediaList)
	{
		if(mediaList.isEmpty())
			return;
		final HashSet<Uri> srcContentUris = new HashSet<>();
		mediaList.getAllContentUris(srcContentUris);
		MediaManager.accessContentProvider(CONTENT_URI_FILE, new MediaManager.ContentProviderAccessCallback()
		{
			@Override
			public void onAccessContentProvider(ContentResolver contentResolver, Uri contentUri, ContentProviderClient client) throws RemoteException
			{
				String sortOrder = mediaList.getComparator().getContentProviderSortOrder();
				int maxMediaCount = mediaList.getMaxMediaCount();
				if(maxMediaCount >= 0)
					sortOrder += (" LIMIT " + maxMediaCount);
				Handler handler = getHandler();
				Cursor cursor = client.query(contentUri, MediaStoreMedia.MEDIA_COLUMNS, m_QueryCondition, m_QueryConditionArgs, sortOrder);
				if(cursor != null)
				{
					try
					{
						// add media
						while(cursor.moveToNext())
						{
							Uri uri = MediaStoreMedia.getContentUri(cursor);
							if(uri == null || srcContentUris.remove(uri))
								continue;
							Media media = MediaStoreMedia.create(cursor, handler);
							if(media != null)
								HandlerUtils.sendMessage(MediaStoreMediaSet.this, MSG_ADD_MEDIA_TO_MEDIA_LIST, new Object[]{ mediaList, media });
						}
						
						// remove media
						if(!srcContentUris.isEmpty())
							HandlerUtils.sendMessage(MediaStoreMediaSet.this, MSG_REMOVE_MEDIA_FROM_MEDIA_LIST, new Object[]{ mediaList, srcContentUris });
					}
					finally
					{
						cursor.close();
					}
				}
			}
		});
	}
	
	
	// Remove media from media list.
	private void removeMediaFromMediaList(MediaListImpl mediaList, Media media)
	{
		mediaList.removeMedia(media);
	}
	private void removeMediaFromMediaList(MediaListImpl mediaList, Set<Uri> media)
	{
		mediaList.removeMedia(media);
	}
	
	
	/**
	 * Set condition to query from media store.
	 * @param condition Condition.
	 * @param conditionArgs Arguments for condition.
	 */
	protected void setQueryCondition(String condition, String... conditionArgs)
	{
		this.verifyAccess();
		m_QueryCondition = ("(" + FileColumns.MEDIA_TYPE + "=" + FileColumns.MEDIA_TYPE_IMAGE + " OR " + FileColumns.MEDIA_TYPE + "=" + FileColumns.MEDIA_TYPE_VIDEO + ")");
		m_QueryConditionArgs = conditionArgs;
		if(condition != null)
			m_QueryCondition += (" AND " + condition);
		this.refreshMediaCount(true);
	}
}
