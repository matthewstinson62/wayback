/* BDBResourceIndex
 *
 * Created on 2005/10/18 14:00:00
 *
 * Copyright (C) 2005 Internet Archive.
 *
 * This file is part of the Wayback Machine (crawler.archive.org).
 *
 * Wayback Machine is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or
 * any later version.
 *
 * Wayback Machine is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser Public License
 * along with Wayback Machine; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package org.archive.wayback.cdx;

import java.io.File;
import java.text.ParseException;
import java.util.Iterator;

import org.archive.wayback.cdx.filter.RecordFilter;
import org.archive.wayback.core.SearchResult;
import org.archive.wayback.core.SearchResults;

import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.Transaction;

/**
 * ResourceResults-specific wrapper on top of the BDBJE database.
 * 
 * @author Brad Tofel
 * @version $Date$, $Revision$
 */
public class BDBResourceIndex {
	/**
	 * Maximum BDBJE file size
	 */
	private final static String JE_LOG_FILEMAX = "256000000";
	/**
	 * path to directory containing the BDBJE files
	 */
	private String path;

	/**
	 * name of BDBJE db within the path directory
	 */
	private String dbName;

	/**
	 * BDBJE Environment
	 */
	Environment env = null;

	/**
	 * BDBJE Database
	 */
	Database db = null;

	/**
	 * Constructor
	 * 
	 * @param thePath
	 *            directory where BDBJE files are stored
	 * @param theDbName
	 *            name of BDB database
	 * @param readOnly
	 * 			  whether environment and DB should be opened as writable
	 * @throws DatabaseException 
	 */
	public BDBResourceIndex(final String thePath, final String theDbName,
			final boolean readOnly)
			throws DatabaseException {
		super();
		initializeDB(thePath, theDbName, readOnly);
	}

	/**
	 * @param thePath Directory where BDBJE files are stored
	 * @param theDbName Name of files in thePath
	 * @param readOnly 
	 * @throws DatabaseException
	 */
	protected void initializeDB(final String thePath, final String theDbName,
			final boolean readOnly)
			throws DatabaseException {
		path = thePath;
		dbName = theDbName;

		EnvironmentConfig environmentConfig = new EnvironmentConfig();
		environmentConfig.setReadOnly(readOnly);
		environmentConfig.setAllowCreate(true);
		environmentConfig.setTransactional(true);
		environmentConfig.setConfigParam("je.log.fileMax",JE_LOG_FILEMAX);
		File file = new File(path);
		env = new Environment(file, environmentConfig);
		DatabaseConfig databaseConfig = new DatabaseConfig();
		databaseConfig.setReadOnly(readOnly);
		databaseConfig.setAllowCreate(true);
		databaseConfig.setTransactional(true);
		// perform other database configurations

		db = env.openDatabase(null, dbName, databaseConfig);
	}

	/**
	 * shut down the BDB.
	 * 
	 * @throws DatabaseException
	 */
	public void shutdownDB() throws DatabaseException {

		if (db != null) {
			db.close();
		}

		if (env != null) {
			env.close();
		}
	}

	/**
	 * @param startKey 
	 * @param filter 
	 * @param results 
	 * @param forward 
	 */
	protected void filterRecords(final String startKey, 
			final RecordFilter filter, SearchResults results, final boolean forward) {
		DatabaseEntry key = new DatabaseEntry();
		DatabaseEntry value = new DatabaseEntry();
		
		key.setData(startKey.getBytes());
		key.setPartial(false);
		try {
			Cursor cursor = db.openCursor(null, null);
			OperationStatus status = cursor.getSearchKeyRange(key, value,
					LockMode.DEFAULT);
			
			// if we are in reverse, immediately back up one record:
			if(!forward && (status == OperationStatus.SUCCESS)) {
				status = cursor.getPrev(key, value, LockMode.DEFAULT);
			}
			while (status == OperationStatus.SUCCESS) {

				CDXRecord record = new CDXRecord();
				// TODO: this should throw something:
				String sKey = new String(key.getData());
				String sVal = new String(value.getData());
				
				record.parseLine(sKey + " " + sVal, 0);
				int ruling = filter.filterRecord(record);
				if(ruling == RecordFilter.RECORD_ABORT) {
					break;
				} else if(ruling == RecordFilter.RECORD_INCLUDE) {
					results.addSearchResult(record.toSearchResult(),forward);
				}
				if(forward) {
					status = cursor.getNext(key, value, LockMode.DEFAULT);
				} else {
					status = cursor.getPrev(key, value, LockMode.DEFAULT);					
				}
			}
			cursor.close();
		} catch (DatabaseException dbe) {
			// TODO: let this bubble up as Index error
			dbe.printStackTrace();
		} catch (ParseException e) {
			// TODO: let this bubble up as Index error
			e.printStackTrace();
		}
	}
	// TODO add mechanism for replay which allows passing in of 
	// an exact date, and use a "scrolling window" of the best results, to 
	// allow for returning the N closest results to a particular date, within
	// a specific window of dates...
	
	/**
	 * Add all ResourceResult in results to BDB index
	 * @param results
	 * @throws Exception
	 */
	public void addResults(SearchResults results) throws Exception {
		Iterator itr = results.iterator();
		DatabaseEntry key = new DatabaseEntry();
		DatabaseEntry value = new DatabaseEntry();
		OperationStatus status = null;
		CDXRecord parser = new CDXRecord();
		try {
			Transaction txn = env.beginTransaction(null, null);
			try {
				Cursor cursor = db.openCursor(txn, null);
				while (itr.hasNext()) {
					SearchResult result = (SearchResult) itr.next();
					parser.fromSearchResult(result);
					String keyString = parser.toKey();
					String valueString = parser.toValue();
					key.setData(keyString.getBytes());
					value.setData(valueString.getBytes());
					status = cursor.put(key, value);
					if (status != OperationStatus.SUCCESS) {
						throw new Exception("oops, put had non-success status");
					}
				}
				cursor.close();
				txn.commit();
			} catch (DatabaseException e) {
				if(txn != null) {
					txn.abort();
				}
				e.printStackTrace();
			}
		} catch (DatabaseException e) {
			e.printStackTrace();
		}
	}

	/**
	 * @return Returns the dbName.
	 */
	public String getDbName() {
		return dbName;
	}

	/**
	 * @return Returns the path.
	 */
	public String getPath() {
		return path;
	}
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		try {
			BDBResourceIndex ddb = new BDBResourceIndex(args[0],args[1],true);
			DatabaseEntry key = new DatabaseEntry();
			DatabaseEntry value = new DatabaseEntry();
			
			key.setData(args[2].getBytes());
			key.setPartial(false);
			Cursor cursor = ddb.db.openCursor(null, null);
			OperationStatus status = cursor.getSearchKeyRange(key, value,
					LockMode.DEFAULT);
			
			while (status == OperationStatus.SUCCESS) {

				System.out.println(new String(key.getData()) + " " + new String(value.getData()));
				status = cursor.getNext(key, value, LockMode.DEFAULT);
			}
			cursor.close();
		} catch (DatabaseException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}
}
