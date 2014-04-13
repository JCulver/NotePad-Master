/*
 * Copyright (c) 2014 Jonas Kalderstam.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nononsenseapps.notepad.sync.googleapi;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SyncResult;
import android.database.Cursor;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Pair;

import com.nononsenseapps.helpers.Log;
import com.nononsenseapps.notepad.database.Task;
import com.nononsenseapps.notepad.database.TaskList;
import com.nononsenseapps.notepad.prefs.SyncPrefs;
import com.nononsenseapps.notepad.sync.googleapi.GoogleAPITalker.PreconditionException;
import com.nononsenseapps.utils.time.RFC3339Date;

import org.apache.http.client.ClientProtocolException;
import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;

public class GoogleTaskSync {
	static final String TAG = "nononsenseapps gtasksync";
	public static final String AUTH_TOKEN_TYPE = "Manage your tasks";
	public static final boolean NOTIFY_AUTH_FAILURE = true;
	public static final String PREFS_LAST_SYNC_ETAG = "lastserveretag";
	public static final String PREFS_GTASK_LAST_SYNC_TIME = "gtasklastsync";

	/**
	 * Returns true if sync was successful, false otherwise
	 */
	public static boolean fullSync(final Context context,
			final Account account, final Bundle extras, final String authority,
			final ContentProviderClient provider, final SyncResult syncResult) {

		Log.d(TAG, "fullSync");
		// Is saved at a successful sync
		final long startTime = Calendar.getInstance().getTimeInMillis();

		boolean success = false;
		// Initialize necessary stuff
		final AccountManager accountManager = AccountManager.get(context);
		final GoogleAPITalker apiTalker = new GoogleAPITalker(context);

		try {
			boolean connected = apiTalker.initialize(accountManager, account,
					AUTH_TOKEN_TYPE, NOTIFY_AUTH_FAILURE);

			if (connected) {

				Log.d(TAG, "AuthToken acquired, we are connected...");

				try {
					// IF full sync, download since start of all time
					// Temporary fix for delete all bug
//					if (PreferenceManager.getDefaultSharedPreferences(context)
//							.getBoolean(SyncPrefs.KEY_FULLSYNC, false)) {
						PreferenceManager.getDefaultSharedPreferences(context)
								.edit()
								.putBoolean(SyncPrefs.KEY_FULLSYNC, false)
								.putLong(PREFS_GTASK_LAST_SYNC_TIME, 0)
								.commit();
//					}

					// Download lists from server
					Log.d(TAG, "download lists");
					final List<GoogleTaskList> remoteLists = downloadLists(apiTalker);

					// merge with local complement
					Log.d(TAG, "merge lists");
					mergeListsWithLocalDB(context, account.name, remoteLists);

					// Synchronize lists locally
					Log.d(TAG, "sync lists locally");
					final List<Pair<TaskList, GoogleTaskList>> listPairs = synchronizeListsLocally(
							context, remoteLists);

					// Synchronize lists remotely
					Log.d(TAG, "sync lists remotely");
					final List<Pair<TaskList, GoogleTaskList>> syncedPairs = synchronizeListsRemotely(
							context, listPairs, apiTalker);

					// For each list
					for (Pair<TaskList, GoogleTaskList> syncedPair : syncedPairs) {
						// Download tasks from server
						Log.d(TAG, "download tasks");
						final List<GoogleTask> remoteTasks = downloadChangedTasks(
								context, apiTalker, syncedPair.second);

						// merge with local complement
						Log.d(TAG, "merge tasks");
						mergeTasksWithLocalDB(context, account.name,
								remoteTasks, syncedPair.first._id);

						// Synchronize tasks locally
						Log.d(TAG, "sync tasks locally");
						final List<Pair<Task, GoogleTask>> taskPairs = synchronizeTasksLocally(
								context, remoteTasks, syncedPair);
						// Synchronize tasks remotely
						Log.d(TAG, "sync tasks remotely");
						synchronizeTasksRemotely(context, taskPairs,
								syncedPair.second, apiTalker);
					}

					Log.d(TAG, "Sync Complete!");
					success = true;
					PreferenceManager.getDefaultSharedPreferences(context)
							.edit()
							.putLong(PREFS_GTASK_LAST_SYNC_TIME, startTime)
							.commit();

					/*
					 * Tasks Step 1: Download changes from the server Step 2:
					 * Iterate and compare with local content Step 2a: If both
					 * versions changed, choose the latest Step 2b: If remote is
					 * newer, put info in local task, save Step 2c: If local is
					 * newer, upload it (in background) Step 3: For remote items
					 * that do not exist locally, save Step 4: For local items
					 * that do not exist remotely, upload
					 */

				}
				catch (ClientProtocolException e) {

					Log.e(TAG,
							"ClientProtocolException: "
									+ e.getLocalizedMessage());
					syncResult.stats.numAuthExceptions++;
				}
				catch (IOException e) {
					syncResult.stats.numIoExceptions++;

					Log.e(TAG, "IOException: " + e.getLocalizedMessage());
				}
				catch (ClassCastException e) {
					// GetListofLists will cast this if it returns a string.
					// It should not return a string but it did...
					syncResult.stats.numAuthExceptions++;
					Log.e(TAG, "ClassCastException: " + e.getLocalizedMessage());
				}

			}
			else {
				// return real failure

				Log.d(TAG, "Could not get authToken. Reporting authException");
				syncResult.stats.numAuthExceptions++;
				// doneIntent.putExtra(SYNC_RESULT, LOGIN_FAIL);
			}

		}
		catch (Exception e) {
			// Something went wrong, don't punish the user
			syncResult.stats.numAuthExceptions++;
			Log.e(TAG, "bobs your uncle: " + e.getLocalizedMessage());
		}
		finally {
			// This must always be called or we will leak resources
			if (apiTalker != null) {
				apiTalker.closeClient();
			}

			Log.d(TAG, "SyncResult: " + syncResult.toDebugString());
		}

		return success;
	}

	/**
	 * Loads the remote lists from the database and merges the two lists. If the
	 * remote list contains all lists, then this method only adds local db-ids
	 * to the items. If it does not contain all of them, this loads whatever
	 * extra items are known in the db to the list also.
	 * 
	 * Since all lists are expected to be downloaded, any non-existing entries
	 * are assumed to be deleted and marked as such.
	 */
	public static void mergeListsWithLocalDB(final Context context,
			final String account, final List<GoogleTaskList> remoteLists) {
		Log.d(TAG, "mergeList starting with: " + remoteLists.size());

		final HashMap<String, GoogleTaskList> localVersions = new HashMap<String, GoogleTaskList>();
		final Cursor c = context.getContentResolver().query(
				GoogleTaskList.URI,
				GoogleTaskList.Columns.FIELDS,
				GoogleTaskList.Columns.ACCOUNT + " IS ? AND "
						+ GoogleTaskList.Columns.SERVICE + " IS ?",
				new String[] { account, GoogleTaskList.SERVICENAME }, null);
		try {
			while (c.moveToNext()) {
				GoogleTaskList list = new GoogleTaskList(c);
				localVersions.put(list.remoteId, list);
			}
		}
		finally {
			if (c != null) c.close();
		}

		for (final GoogleTaskList remotelist : remoteLists) {
			// Merge with hashmap
			if (localVersions.containsKey(remotelist.remoteId)) {
				//Log.d(TAG, "Setting merge id");
				remotelist.dbid = localVersions.get(remotelist.remoteId).dbid;
				//Log.d(TAG, "Setting merge delete status");
				remotelist.setDeleted(localVersions.get(remotelist.remoteId)
						.isDeleted());
				localVersions.remove(remotelist.remoteId);
			}
		}

		// Remaining ones
		for (final GoogleTaskList list : localVersions.values()) {
			list.remotelyDeleted = true;
			remoteLists.add(list);
		}
		Log.d(TAG, "mergeList finishing with: " + remoteLists.size());
	}

	/**
	 * Loads the remote tasks from the database and merges the two lists. If the
	 * remote list contains all items, then this method only adds local db-ids
	 * to the items. If it does not contain all of them, this loads whatever
	 * extra items are known in the db to the list also.
	 */
	public static void mergeTasksWithLocalDB(final Context context,
			final String account, final List<GoogleTask> remoteTasks,
			long listDbId) {
		final HashMap<String, GoogleTask> localVersions = new HashMap<String, GoogleTask>();
		final Cursor c = context.getContentResolver().query(
				GoogleTask.URI,
				GoogleTask.Columns.FIELDS,
				GoogleTask.Columns.LISTDBID + " IS ? AND "
						+ GoogleTask.Columns.ACCOUNT + " IS ? AND "
						+ GoogleTask.Columns.SERVICE + " IS ?",
				new String[] { Long.toString(listDbId), account,
						GoogleTaskList.SERVICENAME }, null);
		try {
			while (c.moveToNext()) {
				GoogleTask task = new GoogleTask(c);
				localVersions.put(task.remoteId, task);
			}
		}
		finally {
			if (c != null) c.close();
		}

		for (final GoogleTask task : remoteTasks) {
			// Set list on remote objects
			task.listdbid = listDbId;
			// Merge with hashmap
			if (localVersions.containsKey(task.remoteId)) {
				task.dbid = localVersions.get(task.remoteId).dbid;
				task.setDeleted(localVersions.get(task.remoteId).isDeleted());
				if (task.isDeleted()) {
					Log.d(TAG, "merge1: deleting " + task.title);
				}
				localVersions.remove(task.remoteId);
			}
		}

		// Remaining ones
		for (final GoogleTask task : localVersions.values()) {
			remoteTasks.add(task);
			if (task.isDeleted()) {
				Log.d(TAG, "merge2: was deleted " + task.title);
			}
		}
	}

	/**
	 * Downloads all lists in GTasks and returns them
	 * 
	 * @throws IOException
	 * @throws ClientProtocolException
	 * @throws JSONException
	 */
	static List<GoogleTaskList> downloadLists(final GoogleAPITalker apiTalker)
			throws ClientProtocolException, IOException, JSONException {
		// Do the actual download
		final ArrayList<GoogleTaskList> remoteLists = new ArrayList<GoogleTaskList>();
		apiTalker.getListOfLists(remoteLists);

		// Return list of TaskLists
		return remoteLists;
	}

	/**
	 * Given a list of remote GTaskLists, iterates through it and their versions
	 * (if any) in the local database. If the remote version is newer, the local
	 * version is updated.
	 * 
	 * If local list has a remote id, but it does not exist in the list of
	 * remote lists, then it has been deleted remotely and is deleted locally as
	 * well.
	 * 
	 * Returns a list of pairs (local, remote).
	 */
	public static List<Pair<TaskList, GoogleTaskList>> synchronizeListsLocally(
			final Context context, final List<GoogleTaskList> remoteLists) {
		final SharedPreferences settings = PreferenceManager
				.getDefaultSharedPreferences(context);
		final ArrayList<Pair<TaskList, GoogleTaskList>> listPairs = new ArrayList<Pair<TaskList, GoogleTaskList>>();
		// For every list
		for (final GoogleTaskList remoteList : remoteLists) {
			// Compare with local
			Log.d(TAG, "Loading remote lists from db");
			TaskList localList = loadRemoteListFromDB(context, remoteList);

			if (localList == null) {
				if (remoteList.remotelyDeleted) {
					Log.d(TAG, "List was remotely deleted1");
					// Deleted locally AND on server
					remoteList.delete(context);
				}
				else if (remoteList.isDeleted()) {
					Log.d(TAG, "List was locally deleted");
					// Was deleted locally
				}
				else {
					// is a new list
					Log.d(TAG, "Inserting new list: " + remoteList.title);
					localList = new TaskList();
					localList.title = remoteList.title;
					localList.save(context, remoteList.updated);
					// Save id in remote also
					remoteList.dbid = localList._id;
					remoteList.save(context);
				}
			}
			else {
				// If local is newer, update remote object
				if (remoteList.remotelyDeleted) {
					Log.d(TAG, "Remote list was deleted2: " + remoteList.title);
					localList.delete(context);
					localList = null;
					remoteList.delete(context);
				}
				else if (localList.updated > remoteList.updated) {
					Log.d(TAG, "Local list newer");
					remoteList.title = localList.title;
					// Updated is set by Google
				}
				else if (localList.updated.equals(remoteList.updated)) {
					// Nothing to do
				}
				else {
					Log.d(TAG, "Updating local list: " + remoteList.title);
					// If remote is newer, update local and save to db
					localList.title = remoteList.title;
					localList.save(context, remoteList.updated);
				}
			}
			if (!remoteList.remotelyDeleted)
				listPairs.add(new Pair<TaskList, GoogleTaskList>(localList,
						remoteList));
		}

		// Add local lists without a remote version to pairs
		for (final TaskList tl : loadNewListsFromDB(context, remoteLists.get(0))) {
			Log.d(TAG, "loading new list db: " + tl.title);
			listPairs.add(new Pair<TaskList, GoogleTaskList>(tl, null));
		}

		// return pairs
		return listPairs;
	}

	static List<Pair<TaskList, GoogleTaskList>> synchronizeListsRemotely(
			final Context context,
			final List<Pair<TaskList, GoogleTaskList>> listPairs,
			final GoogleAPITalker apiTalker) throws ClientProtocolException,
			IOException, PreconditionException, JSONException {
		final List<Pair<TaskList, GoogleTaskList>> syncedPairs = new ArrayList<Pair<TaskList, GoogleTaskList>>();
		// For every list
		for (final Pair<TaskList, GoogleTaskList> pair : listPairs) {
			Pair<TaskList, GoogleTaskList> syncedPair = pair;
			if (pair.second == null) {
				// New list to create
				final GoogleTaskList newList = new GoogleTaskList(pair.first,
						apiTalker.accountName);
				apiTalker.uploadList(newList);
				// Save to db also
				newList.save(context);
				pair.first.save(context, newList.updated);
				syncedPair = new Pair<TaskList, GoogleTaskList>(pair.first,
						newList);
			}
			else if (pair.second.isDeleted()) {
				Log.d(TAG, "remotesync: isDeletedLocally");
				// Deleted locally, delete remotely also
				pair.second.remotelyDeleted = true;
				try {
					apiTalker.uploadList(pair.second);
				}
				catch (PreconditionException e) {
					// Deleted the default list. Ignore error
				}
				// and delete from db if it exists there
				pair.second.delete(context);
				syncedPair = null;
			}
			else if (pair.first.updated > pair.second.updated) {
				// If local update is different than remote, that means we
				// should update
				apiTalker.uploadList(pair.second);
				// No need to save remote object
				pair.first.save(context, pair.second.updated);
			}
			// else remote has already been saved locally, nothing to upload
			if (syncedPair != null) {
				syncedPairs.add(syncedPair);
			}
		}
		// return (updated) pairs
		return syncedPairs;
	}

	static void synchronizeTasksRemotely(final Context context,
			final List<Pair<Task, GoogleTask>> taskPairs,
			final GoogleTaskList gTaskList, final GoogleAPITalker apiTalker)
			throws ClientProtocolException, IOException, PreconditionException,
			JSONException {
		for (final Pair<Task, GoogleTask> pair : taskPairs) {

			// if newly created locally
			if (pair.second == null) {
				final GoogleTask newTask = new GoogleTask(pair.first,
						apiTalker.accountName);
				apiTalker.uploadTask(newTask, gTaskList);
				newTask.save(context);
				pair.first.save(context, newTask.updated);
			}
			// if deleted locally
			else if (pair.second.isDeleted()) {
				Log.d(TAG, "remotetasksync: isDeletedLocally");
				// Delete remote also
				pair.second.remotelydeleted = true;
				apiTalker.uploadTask(pair.second, gTaskList);
				// Remove from db
				pair.second.delete(context);
			}
			// if local updated is different from remote,
			// should update remote
			else if (pair.first.updated > pair.second.updated) {
				apiTalker.uploadTask(pair.second, gTaskList);
				// No need to save remote object here
				pair.first.save(context, pair.second.updated);
			}
		}
	}

	static TaskList loadRemoteListFromDB(final Context context,
			final GoogleTaskList remoteList) {
		if (remoteList.dbid == null || remoteList.dbid < 1) return null;

		final Cursor c = context.getContentResolver().query(
				TaskList.getUri(remoteList.dbid), TaskList.Columns.FIELDS,
				null, null, null);
		TaskList tl = null;
		try {
			if (c.moveToFirst()) {
				tl = new TaskList(c);
			}
		}
		finally {
			if (c != null) c.close();
		}

		return tl;
	}

	static List<TaskList> loadNewListsFromDB(final Context context,
			final GoogleTaskList remoteList) {
		final Cursor c = context.getContentResolver().query(TaskList.URI,
				TaskList.Columns.FIELDS,
				GoogleTaskList.getTaskListWithoutRemoteClause(),
				remoteList.getTaskListWithoutRemoteArgs(), null);
		final ArrayList<TaskList> lists = new ArrayList<TaskList>();
		try {
			while (c.moveToNext()) {
				lists.add(new TaskList(c));
			}
		}
		finally {
			if (c != null) c.close();
		}

		return lists;
	}

	static List<Task> loadNewTasksFromDB(final Context context,
			final long listdbid, final String account) {
		final Cursor c = context.getContentResolver().query(
				Task.URI,
				Task.Columns.FIELDS,
				GoogleTask.getTaskWithoutRemoteClause(),
				GoogleTask.getTaskWithoutRemoteArgs(listdbid, account,
						GoogleTaskList.SERVICENAME), null);
		final ArrayList<Task> tasks = new ArrayList<Task>();
		try {
			while (c.moveToNext()) {
				tasks.add(new Task(c));
			}
		}
		finally {
			if (c != null) c.close();
		}

		return tasks;
	}

	static List<GoogleTask> downloadChangedTasks(final Context context,
			final GoogleAPITalker apiTalker, final GoogleTaskList remoteList)
			throws ClientProtocolException, IOException, JSONException {
//		final SharedPreferences settings = PreferenceManager
//				.getDefaultSharedPreferences(context);
//		RFC3339Date.asRFC3339(settings.getLong(
//				PREFS_GTASK_LAST_SYNC_TIME, 0))

		final List<GoogleTask> remoteTasks = apiTalker.getModifiedTasks(
				null, remoteList);

		return remoteTasks;
	}

	static Task loadRemoteTaskFromDB(final Context context,
			final GoogleTask remoteTask) {
		final Cursor c = context.getContentResolver().query(Task.URI,
				Task.Columns.FIELDS, remoteTask.getTaskWithRemoteClause(),
				remoteTask.getTaskWithRemoteArgs(), null);
		Task t = null;
		try {
			if (c.moveToFirst()) {
				t = new Task(c);
			}
		}
		finally {
			if (c != null) c.close();
		}

		return t;
	}

	public static List<Pair<Task, GoogleTask>> synchronizeTasksLocally(
			final Context context, final List<GoogleTask> remoteTasks,
			final Pair<TaskList, GoogleTaskList> listPair) {
		final SharedPreferences settings = PreferenceManager
				.getDefaultSharedPreferences(context);
		final ArrayList<Pair<Task, GoogleTask>> taskPairs = new ArrayList<Pair<Task, GoogleTask>>();
		// For every list
		for (final GoogleTask remoteTask : remoteTasks) {
			// Compare with local
			Task localTask = loadRemoteTaskFromDB(context, remoteTask);

			// When no local version was found, either
			// a) it was deleted by the user or
			// b) it was created on the server
			if (localTask == null) {
				if (remoteTask.remotelydeleted) {
					Log.d(TAG, "slocal: task was remotely deleted1: " + remoteTask.title);
					// Nothing to do
					remoteTask.delete(context);
				}
				else if (remoteTask.isDeleted()) {
					Log.d(TAG, "slocal: task was locally deleted: " + remoteTask.remoteId);
					// upload change
				}
				else {
					//Log.d(TAG, "slocal: task was new: " + remoteTask.title);
					// If no local, and updated is higher than latestupdate,
					// create new
					localTask = new Task();
					localTask.title = remoteTask.title;
					localTask.note = remoteTask.notes;
					localTask.dblist = remoteTask.listdbid;
					// Don't touch time
					if (remoteTask.dueDate != null
							&& !remoteTask.dueDate.isEmpty()) {
						try {
							localTask.due = RFC3339Date.combineDateAndTime(remoteTask.dueDate, localTask.due);
						}
						catch (Exception e) {
						}
					}
					if (remoteTask.status != null
							&& remoteTask.status.equals(GoogleTask.COMPLETED)) {
						localTask.completed = remoteTask.updated;
					}

					localTask.save(context, remoteTask.updated);
					// Save id in remote also
					remoteTask.dbid = localTask._id;
					remoteTask.save(context);
				}
			}
			else {
				// If local is newer, update remote object
				if (localTask.updated > remoteTask.updated) {
					remoteTask.fillFrom(localTask);
					// Updated is set by Google
				}
				// Remote is newer
				else if (remoteTask.remotelydeleted) {
					Log.d(TAG, "slocal: task was remotely deleted2: " + remoteTask.title);
					localTask.delete(context);
					localTask = null;
					remoteTask.delete(context);
				}
				else if (localTask.updated.equals(remoteTask.updated)) {
					// Nothing to do, we are already updated
				}
				else {
					//Log.d(TAG, "slocal: task was remotely updated: " + remoteTask.title);
					// If remote is newer, update local and save to db
					localTask.title = remoteTask.title;
					localTask.note = remoteTask.notes;
					localTask.dblist = remoteTask.listdbid;
					if (remoteTask.dueDate != null
							&& !remoteTask.dueDate.isEmpty()) {
						try {
							// dont touch time
							localTask.due = RFC3339Date.combineDateAndTime(remoteTask.dueDate, localTask.due);
						}
						catch (Exception e) {
							localTask.due = null;
						}
					}
					else {
						localTask.due = null;
					}
					
					if (remoteTask.status != null
							&& remoteTask.status.equals(GoogleTask.COMPLETED)) {
						// Only change this if it is not already completed
						if (localTask.completed == null) {
							localTask.completed = remoteTask.updated;
						}
					}
					else {
						localTask.completed = null;
					}

					localTask.save(context, remoteTask.updated);
				}
			}
			if (remoteTask.remotelydeleted) {
				// Dont
				Log.d(TAG, "skipping remotely deleted");
			}
			else if (localTask != null && remoteTask != null
					&& localTask.updated.equals(remoteTask.updated)) {
				//Log.d("nononsenseapps gtasksync", "skipping equal update");
				// Dont
			}
			else {
//				if (localTask != null) {
//					Log.d("nononsenseapps gtasksync", "going to upload: " + localTask.title + ", l." + localTask.updated + " r." + remoteTask.updated);
//				}
				taskPairs
						.add(new Pair<Task, GoogleTask>(localTask, remoteTask));
			}
		}

		// Add local lists without a remote version to pairs
		for (final Task t : loadNewTasksFromDB(context, listPair.first._id,
				listPair.second.account)) {
			//Log.d("nononsenseapps gtasksync", "adding local only: " + t.title);
			taskPairs.add(new Pair<Task, GoogleTask>(t, null));
		}

		// return pairs
		return taskPairs;
	}

	// private void sortByRemoteParent(final ArrayList<GoogleTask> tasks) {
	// final HashMap<String, Integer> levels = new HashMap<String, Integer>();
	// levels.put(null, -1);
	// final ArrayList<GoogleTask> tasksToDo = (ArrayList<GoogleTask>) tasks
	// .clone();
	// GoogleTask lastFailed = null;
	// int current = -1;
	// Log.d("remoteorder", "Doing remote sorting with size: " + tasks.size());
	// while (!tasksToDo.isEmpty()) {
	// current = current >= (tasksToDo.size() - 1) ? 0 : current + 1;
	// Log.d("remoteorder", "current: " + current);
	//
	// if (levels.containsKey(tasksToDo.get(current).parent)) {
	// Log.d("remoteorder", "parent in levelmap");
	// levels.put(tasksToDo.get(current).id,
	// levels.get(tasksToDo.get(current).parent) + 1);
	// tasksToDo.remove(current);
	// current -= 1;
	// lastFailed = null;
	// }
	// else if (lastFailed == null) {
	// Log.d("remoteorder", "lastFailed null, now " + current);
	// lastFailed = tasksToDo.get(current);
	// }
	// else if (lastFailed.equals(tasksToDo.get(current))) {
	// Log.d("remoteorder", "lastFailed == current");
	// // Did full lap, parent is not new
	// levels.put(tasksToDo.get(current).id, 99);
	// levels.put(tasksToDo.get(current).parent, 98);
	// tasksToDo.remove(current);
	// current -= 1;
	// lastFailed = null;
	// }
	// }
	//
	// // Just to make sure that new notes appear first in insertion order
	// Collections.sort(tasks, new GoogleTask.RemoteOrder(levels));
	// }
}
