/*
 *    This file is part of mlDHT.
 * 
 *    mlDHT is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 2 of the License, or
 *    (at your option) any later version.
 * 
 *    mlDHT is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 * 
 *    You should have received a copy of the GNU General Public License
 *    along with mlDHT.  If not, see <http://www.gnu.org/licenses/>.
 */
package lbms.plugins.mldht.kad;

import static the8472.bencode.Utils.prettyPrint;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import lbms.plugins.mldht.DHTConfiguration;
import lbms.plugins.mldht.kad.GenericStorage.StorageItem;
import lbms.plugins.mldht.kad.GenericStorage.UpdateResult;
import lbms.plugins.mldht.kad.Node.RoutingTableEntry;
import lbms.plugins.mldht.kad.messages.AbstractLookupRequest;
import lbms.plugins.mldht.kad.messages.AbstractLookupResponse;
import lbms.plugins.mldht.kad.messages.AnnounceRequest;
import lbms.plugins.mldht.kad.messages.AnnounceResponse;
import lbms.plugins.mldht.kad.messages.ErrorMessage;
import lbms.plugins.mldht.kad.messages.ErrorMessage.ErrorCode;
import lbms.plugins.mldht.kad.messages.FindNodeRequest;
import lbms.plugins.mldht.kad.messages.FindNodeResponse;
import lbms.plugins.mldht.kad.messages.GetPeersRequest;
import lbms.plugins.mldht.kad.messages.GetPeersResponse;
import lbms.plugins.mldht.kad.messages.GetRequest;
import lbms.plugins.mldht.kad.messages.GetResponse;
import lbms.plugins.mldht.kad.messages.MessageBase;
import lbms.plugins.mldht.kad.messages.PingRequest;
import lbms.plugins.mldht.kad.messages.PingResponse;
import lbms.plugins.mldht.kad.messages.PutRequest;
import lbms.plugins.mldht.kad.messages.PutResponse;
import lbms.plugins.mldht.kad.messages.UnknownTypeResponse;
import lbms.plugins.mldht.kad.tasks.AnnounceTask;
import lbms.plugins.mldht.kad.tasks.NodeLookup;
import lbms.plugins.mldht.kad.tasks.PeerLookupTask;
import lbms.plugins.mldht.kad.tasks.PingRefreshTask;
import lbms.plugins.mldht.kad.tasks.Task;
import lbms.plugins.mldht.kad.tasks.TaskListener;
import lbms.plugins.mldht.kad.tasks.TaskManager;
import lbms.plugins.mldht.kad.utils.AddressUtils;
import lbms.plugins.mldht.kad.utils.ByteWrapper;
import lbms.plugins.mldht.kad.utils.PopulationEstimator;
import lbms.plugins.mldht.utils.NIOConnectionManager;

/**
 * @author Damokles
 * 
 */
public class DHT implements DHTBase {
	
	public static enum DHTtype {
		IPV4_DHT("IPv4",20+4+2, 4+2, Inet4Address.class,20+8, 1400),
		IPV6_DHT("IPv6",20+16+2, 16+2, Inet6Address.class,40+8, 1200);
		
		public final int							HEADER_LENGTH;
		public final int 							NODES_ENTRY_LENGTH;
		public final int							ADDRESS_ENTRY_LENGTH;
		public final Class<? extends InetAddress>	PREFERRED_ADDRESS_TYPE;
		public final int							MAX_PACKET_SIZE;
		public final String 						shortName;
		private DHTtype(String shortName, int nodeslength, int addresslength, Class<? extends InetAddress> addresstype, int header, int maxSize) {
			this.shortName = shortName;
			this.NODES_ENTRY_LENGTH = nodeslength;
			this.PREFERRED_ADDRESS_TYPE = addresstype;
			this.ADDRESS_ENTRY_LENGTH = addresslength;
			this.HEADER_LENGTH = header;
			this.MAX_PACKET_SIZE = maxSize;
		}

	}


	private static DHTLogger				logger;
	private static LogLevel					logLevel	= LogLevel.Info;

	private volatile static ScheduledThreadPoolExecutor	defaultScheduler;
	private static ThreadGroup					executorGroup;
	
	static {

		logger = new DHTLogger() {
			public void log (String message, LogLevel l) {
				System.out.println(message);
			};

			/*
			 * (non-Javadoc)
			 * 
			 * @see lbms.plugins.mldht.kad.DHTLogger#log(java.lang.Exception)
			 */
			public void log (Throwable e, LogLevel l) {
				e.printStackTrace();
			}
		};
	}

	private boolean							running;

	private boolean							bootstrapping;
	private long							lastBootstrap;

	DHTConfiguration						config;
	private Node							node;
	private RPCServerManager				serverManager;
	private GenericStorage					storage;
	private Database						db;
	private TaskManager						tman;
	private Path							table_file;
	private boolean							useRouterBootstrapping;

	private List<DHTStatsListener>			statsListeners;
	private List<DHTStatusListener>			statusListeners;
	private List<DHTIndexingListener>		indexingListeners;
	private DHTStats						stats;
	private DHTStatus						status;
	private PopulationEstimator				estimator;
	private AnnounceNodeCache				cache;
	private NIOConnectionManager			connectionManager;
	
	RPCStats								serverStats;

	private final DHTtype					type;
	private List<ScheduledFuture<?>>		scheduledActions = new ArrayList<ScheduledFuture<?>>();
	private List<DHT>						siblingGroup = new ArrayList<>();
	private ScheduledExecutorService		scheduler;
	

	public DHT(DHTtype type) {
		this.type = type;
		
		siblingGroup.add(this);
		stats = new DHTStats();
		status = DHTStatus.Stopped;
		statsListeners = new ArrayList<DHTStatsListener>(2);
		statusListeners = new ArrayList<DHTStatusListener>(2);
		indexingListeners = new ArrayList<DHTIndexingListener>();
		estimator = new PopulationEstimator();
	}
	
	public ScheduledExecutorService getScheduler() {
		return scheduler;
	}

	public void setScheduler(ScheduledExecutorService scheduler) {
		this.scheduler = scheduler;
	}

	public void addSiblings(List<DHT> toAdd) {
		toAdd.forEach(s -> {
			if(siblingGroup.contains(s))
				siblingGroup.add(s);
		});
	}
	
	public Optional<DHT> getSiblingByType(DHTtype type) {
		return siblingGroup.stream().filter(sib -> sib.getType() == type).findAny();
	}
	
	public List<DHT> getSiblings() {
		return Collections.unmodifiableList(siblingGroup);
	}
	
	public static interface IncomingMessageListener {
		void received(DHT dht, MessageBase msg);
	}
	
	private List<IncomingMessageListener> incomingMessageListeners = new ArrayList<>();
	
	public void addIncomingMessageListener(IncomingMessageListener l) {
		incomingMessageListeners.add(l);
	}
	
	void incomingMessage(MessageBase msg) {
		incomingMessageListeners.forEach(e -> e.received(this, msg));
	}
	
	public void ping (PingRequest r) {
		if (!isRunning()) {
			return;
		}

		// ignore requests we get from ourself
		if (node.isLocalId(r.getID())) {
			return;
		}

		PingResponse rsp = new PingResponse(r.getMTID());
		rsp.setDestination(r.getOrigin());
		r.getServer().sendMessage(rsp);
		
		node.recieved(r);
	}

	public void findNode(AbstractLookupRequest r) {
		if (!isRunning()) {
			return;
		}

		// ignore requests we get from ourself
		if (node.isLocalId(r.getID())) {
			return;
		}

		AbstractLookupResponse response;
		if(r instanceof FindNodeRequest)
			response = new FindNodeResponse(r.getMTID());
		else
			response = new UnknownTypeResponse(r.getMTID());
		
		populateResponse(r.getTarget(), response, r.doesWant4() ? DHTConstants.MAX_ENTRIES_PER_BUCKET : 0, r.doesWant6() ? DHTConstants.MAX_ENTRIES_PER_BUCKET : 0);
		
		response.setDestination(r.getOrigin());
		r.getServer().sendMessage(response);

		node.recieved(r);
	}
	
	void populateResponse(Key target, AbstractLookupResponse rsp, int v4, int v6) {
		if(v4 > 0) {
			getSiblingByType(DHTtype.IPV4_DHT).ifPresent(sib -> {
				KClosestNodesSearch kns = new KClosestNodesSearch(target, v4, sib);
				kns.fill(DHTtype.IPV4_DHT != type);
				rsp.setNodes(kns.asNodeList());
			});
		}
		
		if(v6 > 0) {
			getSiblingByType(DHTtype.IPV6_DHT).ifPresent(sib -> {
				KClosestNodesSearch kns = new KClosestNodesSearch(target, v6, sib);
				kns.fill(DHTtype.IPV6_DHT != type);
				rsp.setNodes6(kns.asNodeList());
			});
		}
	}

	public void response (MessageBase r) {
		if (!isRunning()) {
			return;
		}

		node.recieved(r);
	}
	
	public void get(GetRequest req) {
		if (!isRunning()) {
			return;
		}
		
		GetResponse rsp = new GetResponse(req.getMTID());
		
		populateResponse(req.getTarget(), rsp, req.doesWant4() ? DHTConstants.MAX_ENTRIES_PER_BUCKET : 0, req.doesWant6() ? DHTConstants.MAX_ENTRIES_PER_BUCKET : 0);
		
		Key k = req.getTarget();
		
		
		Optional.ofNullable(db.genToken(req.getID(), req.getOrigin().getAddress(), req.getOrigin().getPort(), k)).ifPresent(token -> {
			rsp.setToken(token.arr);
		});
		
		storage.get(k).ifPresent(item -> {
			rsp.setRawValue(ByteBuffer.wrap(item.value));
			rsp.setKey(item.pubkey);
			rsp.setSignature(item.signature);
			if(item.sequenceNumber >= 0)
				rsp.setSequenceNumber(item.sequenceNumber);
		});
		
		rsp.setDestination(req.getOrigin());
		
		
		req.getServer().sendMessage(rsp);
		
		node.recieved(req);
	}
	
	public void put(PutRequest req) {
		
		Key k = req.deriveTargetKey();
		
		if(!db.checkToken(new ByteWrapper(req.getToken()), req.getID(), req.getOrigin().getAddress(), req.getOrigin().getPort(), k)) {
			sendError(req, ErrorCode.ProtocolError.code, "received invalid or expired token for PUT request");
			return;
		}
		
		UpdateResult result = storage.putOrUpdate(k, new StorageItem(req), req.getExpectedSequenceNumber());
		
		switch(result) {
			case CAS_FAIL:
				sendError(req, ErrorCode.CasFail.code, "CAS failure");
				return;
			case SIG_FAIL:
				sendError(req, ErrorCode.InvalidSignature.code, "signature validation failed");
				return;
			case SEQ_FAIL:
				sendError(req, ErrorCode.CasNotMonotonic.code, "sequence number less than current");
				return;
			case IMMUTABLE_SUBSTITUTION_FAIL:
				sendError(req, ErrorCode.ProtocolError.code, "PUT request replacing mutable data with immutable is not supported");
				return;
			case SUCCESS:
				
				PutResponse rsp = new PutResponse(req.getMTID());
				rsp.setDestination(req.getOrigin());
				
				req.getServer().sendMessage(rsp);
				break;
		}

		
		node.recieved(req);
	}

	public void getPeers (GetPeersRequest r) {
		if (!isRunning()) {
			return;
		}

		// ignore requests we get from ourself
		if (node.isLocalId(r.getID())) {
			return;
		}

		BloomFilterBEP33 peerFilter = r.isScrape() ? db.createScrapeFilter(r.getInfoHash(), false) : null;
		BloomFilterBEP33 seedFilter = r.isScrape() ? db.createScrapeFilter(r.getInfoHash(), true) : null;
		
		boolean v6 = Inet6Address.class.isAssignableFrom(type.PREFERRED_ADDRESS_TYPE);
		
		boolean heavyWeight = peerFilter != null;
		
		int valuesTargetLength = v6 ? 35 : 50;
		// scrape filter gobble up a lot of space, restrict list sizes
		if(heavyWeight)
			valuesTargetLength =  v6 ? 15 : 30;
		
		List<DBItem> dbl = db.sample(r.getInfoHash(), valuesTargetLength,type, r.isNoSeeds());

		for(DHTIndexingListener listener : indexingListeners)
		{
			List<PeerAddressDBItem> toAdd = listener.incomingPeersRequest(r.getInfoHash(), r.getOrigin().getAddress(), r.getID());
			if(dbl == null && !toAdd.isEmpty())
				dbl = new ArrayList<DBItem>();
			if(dbl != null && !toAdd.isEmpty())
				dbl.addAll(toAdd);
		}
		
		// generate a token
		ByteWrapper token = null;
		if(db.insertForKeyAllowed(r.getInfoHash()))
			token = db.genToken(r.getID(), r.getOrigin().getAddress(), r.getOrigin().getPort(), r.getInfoHash());

		int want4 = r.doesWant4() ? DHTConstants.MAX_ENTRIES_PER_BUCKET : 0;
		int want6 = r.doesWant6() ? DHTConstants.MAX_ENTRIES_PER_BUCKET : 0;

		if(v6 && peerFilter != null)
			want6 = Math.min(5, want6);
		
		// bloom filters + token + values => we can't include both sets of nodes, even if the node requests it
		if(heavyWeight) {
			if(v6)
				want4 = 0;
			else
				want6 = 0;
		}
		

		
		GetPeersResponse resp = new GetPeersResponse(r.getMTID());
		
		populateResponse(r.getTarget(), resp, want4, want6);
		
		resp.setToken(token != null ? token.arr : null);
		resp.setScrapePeers(peerFilter);
		resp.setScrapeSeeds(seedFilter);

		
		resp.setPeerItems(dbl);
		resp.setDestination(r.getOrigin());
		r.getServer().sendMessage(resp);

		node.recieved(r);
	}

	public void announce (AnnounceRequest r) {
		if (!isRunning()) {
			return;
		}

		// ignore requests we get from ourself
		if (node.isLocalId(r.getID())) {
			return;
		}

		// first check if the token is OK
		ByteWrapper token = new ByteWrapper(r.getToken());
		if (!db.checkToken(token, r.getID(), r.getOrigin().getAddress(), r.getOrigin().getPort(), r.getInfoHash())) {
			logDebug("DHT Received Announce Request with invalid token.");
			sendError(r, ErrorCode.ProtocolError.code, "Invalid Token; tokens expire after "+DHTConstants.TOKEN_TIMEOUT+"ms; only valid for the IP/port to which it was issued; only valid for the infohash for which it was issued");
			return;
		}

		logDebug("DHT Received Announce Request, adding peer to db: "
				+ r.getOrigin().getAddress());

		// everything OK, so store the value
		PeerAddressDBItem item = PeerAddressDBItem.createFromAddress(r.getOrigin().getAddress(), r.getPort(), r.isSeed());
		r.getVersion().ifPresent(item::setVersion);
		if(!AddressUtils.isBogon(item))
			db.store(r.getInfoHash(), item);

		// send a proper response to indicate everything is OK
		AnnounceResponse rsp = new AnnounceResponse(r.getMTID());
		rsp.setDestination(r.getOrigin());
		r.getServer().sendMessage(rsp);

		node.recieved(r);
	}

	public void error (ErrorMessage r) {
		StringBuilder b = new StringBuilder();
		b.append("Error [").append( r.getCode() ).append("] from: " ).append(r.getOrigin());
		b.append(" Message: \"").append(r.getMessage()).append("\"");
		r.getVersion().ifPresent(v -> b.append(" version:").append(prettyPrint(v)));
		
		DHT.logError(b.toString());
	}

	public void timeout (RPCCall r) {
		if (isRunning()) {
			node.onTimeout(r);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#addDHTNode(java.lang.String, int)
	 */
	public void addDHTNode (String host, int hport) {
		if (!isRunning()) {
			return;
		}
		InetSocketAddress addr = new InetSocketAddress(host, hport);

		if (!addr.isUnresolved() && !AddressUtils.isBogon(addr)) {
			if(!type.PREFERRED_ADDRESS_TYPE.isInstance(addr.getAddress()) || node.getNumEntriesInRoutingTable() > DHTConstants.BOOTSTRAP_IF_LESS_THAN_X_PEERS)
				return;
			serverManager.getRandomActiveServer(true).ping(addr);
		}

	}

	/**
	 * returns a non-enqueued task for further configuration. or zero if the request cannot be serviced.
	 * use the task-manager to actually start the task.
	 */
	public PeerLookupTask createPeerLookup (byte[] info_hash) {
		if (!isRunning()) {
			return null;
		}
		Key id = new Key(info_hash);
		
		RPCServer srv = serverManager.getRandomActiveServer(false);
		if(srv == null)
			return null;

		PeerLookupTask lookupTask = new PeerLookupTask(srv, node, id);

		return lookupTask;
	}
	
	public AnnounceTask announce(PeerLookupTask lookup, boolean isSeed, int btPort) {
		if (!isRunning()) {
			return null;
		}
		
		// reuse the same server to make sure our tokens are still valid
		AnnounceTask announce = new AnnounceTask(lookup.getRPC(), node, lookup.getInfoHash(), btPort);
		announce.setSeed(isSeed);
		for (KBucketEntryAndToken kbe : lookup.getAnnounceCanidates())
		{
			announce.addToTodo(kbe);
		}

		tman.addTask(announce);

		return announce;
	}
	
	public GenericStorage getStorage() {
		return storage;
	}
	
	
	public DHTConfiguration getConfig() {
		return config;
	}
	
	public AnnounceNodeCache getCache() {
		return cache;
	}
	
	public RPCServerManager getServerManager() {
		return serverManager;
	}
	
	public NIOConnectionManager getConnectionManager() {
		return connectionManager;
	}
	
	public PopulationEstimator getEstimator() {
		return estimator;
	}

	public DHTtype getType() {
		return type;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#getStats()
	 */
	public DHTStats getStats () {
		return stats;
	}

	/**
	 * @return the status
	 */
	public DHTStatus getStatus () {
		return status;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#isRunning()
	 */
	public boolean isRunning () {
		return running;
	}

	private int getPort() {
		int port = config.getListeningPort();
		if(port < 1 || port > 65535)
			port = 49001;
		return port;
	}


	void populate() {
		serverStats = new RPCStats();

		
		cache = new AnnounceNodeCache();
		stats.setRpcStats(serverStats);
		
		serverManager = new RPCServerManager(this);
		node = new Node(this);
		db = new Database();
		stats.setDbStats(db.getStats());
		tman = new TaskManager(this);
		running = true;
		storage = new GenericStorage();
	}
	
	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#start(java.lang.String, int)
	 */
	public void start (DHTConfiguration config)
			throws SocketException {
		if (running) {
			return;
		}
		
		if(this.scheduler == null)
			this.scheduler = getDefaultScheduler();
		this.config = config;
		useRouterBootstrapping = !config.noRouterBootstrap();
		
		table_file = config.getStoragePath().resolve(type.shortName+"-table.cache");

		setStatus(DHTStatus.Initializing);
		stats.resetStartedTimestamp();

		logInfo("Starting DHT on port " + getPort());
		resolveBootstrapAddresses();
		
		connectionManager = new NIOConnectionManager("mlDHT "+type.shortName+" NIO Selector");
		
		populate();
		
		node.initKey(config);
		node.loadTable(table_file);
		

		// these checks are fairly expensive on large servers (network interface enumeration)
		// schedule them separately
		scheduledActions.add(scheduler.scheduleWithFixedDelay(serverManager::doBindChecks, 10, 10, TimeUnit.SECONDS));
		
		scheduledActions.add(scheduler.scheduleWithFixedDelay(() -> {
			// maintenance that should run all the time, before the first queries
			tman.dequeue();

			if (running)
				onStatsUpdate();
		}, 5000, DHTConstants.DHT_UPDATE_INTERVAL, TimeUnit.MILLISECONDS));

		// initialize as many RPC servers as we need
		serverManager.refresh(System.currentTimeMillis());
		
		
		bootstrapping = true;
		
		
		started();


		
//		// does 10k random lookups and prints them to a file for analysis
//		scheduler.schedule(new Runnable() {
//			//PrintWriter		pw;
//			TaskListener	li	= new TaskListener() {
//									public synchronized void finished(Task t) {
//										NodeLookup nl = ((NodeLookup) t);
//										if (nl.closestSet.size() < DHTConstants.MAX_ENTRIES_PER_BUCKET)
//											return;
//										/*
//										StringBuilder b = new StringBuilder();
//										b.append(nl.targetKey.toString(false));
//										b.append(",");
//										for (Key i : nl.closestSet)
//											b.append(i.toString(false).substring(0, 12) + ",");
//										b.deleteCharAt(b.length() - 1);
//										pw.println(b);
//										pw.flush();
//										*/
//									}
//								};
//
//			public void run() {
//				if(type == DHTtype.IPV6_DHT)
//					return;
//				/*
//				try
//				{
//					pw = new PrintWriter("H:\\mldht.log");
//				} catch (FileNotFoundException e)
//				{
//					e.printStackTrace();
//				}*/
//				for (int i = 0; i < 10000; i++)
//				{
//					NodeLookup l = new NodeLookup(Key.createRandomKey(), srv, node, false);
//					if (canStartTask())
//						l.start();
//					tman.addTask(l);
//					l.addListener(li);
//					if (i == (10000 - 1))
//						l.addListener(new TaskListener() {
//							public void finished(Task t) {
//								System.out.println("10k lookups done");
//							}
//						});
//				}
//			}
//		}, 1, TimeUnit.MINUTES);
		

	}
	
	
	


	public void started () {
		
		for(RoutingTableEntry bucket : node.table().list()) {
			RPCServer srv = serverManager.getRandomServer();
			if(srv == null)
				break;
			Task t = new PingRefreshTask(srv, node, bucket.getBucket(), true);
			t.setInfo("Startup ping for " + bucket.prefix);
			if(t.getTodoCount() > 0)
				tman.addTask(t);
		}
			
		
		
		bootstrapping = false;
		bootstrap();
		
		/*
		if(type == DHTtype.IPV6_DHT)
		{
			Task t = new KeyspaceCrawler(srv, node);
			tman.addTask(t);
		}*/
			
		scheduledActions.add(scheduler.scheduleWithFixedDelay(() -> {
			try {
				update();
			} catch (RuntimeException e) {
				log(e, LogLevel.Fatal);
			}
		}, 5000, DHTConstants.DHT_UPDATE_INTERVAL, TimeUnit.MILLISECONDS));
		
		scheduledActions.add(scheduler.scheduleWithFixedDelay(() -> {
			try
			{
				long now = System.currentTimeMillis();


				db.expire(now);
				cache.cleanup(now);
				storage.cleanup();
			} catch (Exception e)
			{
				log(e, LogLevel.Fatal);
			}

		}, 1000, DHTConstants.CHECK_FOR_EXPIRED_ENTRIES, TimeUnit.MILLISECONDS));
		
		// single ping to a random node per server to check socket liveness
		scheduledActions.add(scheduler.scheduleWithFixedDelay(() -> {
			
			for(RPCServer srv : serverManager.getAllServers()) {
				if(srv.getNumActiveRPCCalls() > 0)
					continue;
				node.getRandomEntry().ifPresent((entry) -> {
					PingRequest req = new PingRequest();
					req.setDestination(entry.getAddress());
					RPCCall call = new RPCCall(req);
					call.setExpectedID(entry.getID());
					srv.doCall(call);
				});
			}
				;
		}, 1, 10, TimeUnit.SECONDS));
		
		
		// deep lookup to make ourselves known to random parts of the keyspace
		scheduledActions.add(scheduler.scheduleWithFixedDelay(() -> {
			try {
				for(RPCServer srv : serverManager.getAllServers())
					findNode(Key.createRandomKey(), false, false, srv).setInfo("Random Refresh Lookup");
			} catch (RuntimeException e1) {
				log(e1, LogLevel.Fatal);
			}
			
			try {
				if(!node.isInSurvivalMode())
					node.saveTable(table_file);
			} catch (IOException e2) {
				e2.printStackTrace();
			}
		}, DHTConstants.RANDOM_LOOKUP_INTERVAL, DHTConstants.RANDOM_LOOKUP_INTERVAL, TimeUnit.MILLISECONDS));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#stop()
	 */
	public void stop () {
		if (!running) {
			return;
		}

		//scheduler.shutdown();
		logInfo("Initated DHT shutdown");
		for (Task t : tman.getActiveTasks()) {
			t.kill();
		}
		
		for(ScheduledFuture<?> future : scheduledActions)
			future.cancel(false);
		// scheduler.getQueue().removeAll(scheduledActions);
		scheduledActions.clear();

		logInfo("stopping servers");
		serverManager.destroy();
		try {
			logInfo("persisting routing table on shutdown");
			node.saveTable(table_file);
			logInfo("table persisted");
		} catch (IOException e) {
			e.printStackTrace();
		}
		running = false;
		stopped();
		tman = null;
		db = null;
		node = null;
		cache = null;
		serverManager = null;
		setStatus(DHTStatus.Stopped);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#getNode()
	 */
	public Node getNode () {
		return node;
	}
	
	public Database getDatabase() {
		return db;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#getTaskManager()
	 */
	public TaskManager getTaskManager () {
		return tman;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#stopped()
	 */
	public void stopped () {
		// TODO Auto-generated method stub

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#update()
	 */
	public void update () {
		
		long now = System.currentTimeMillis();
		
		serverManager.refresh(now);
		
		if (!isRunning()) {
			return;
		}

		node.doBucketChecks(now);

		if (!bootstrapping) {
			if (node.getNumEntriesInRoutingTable() < DHTConstants.BOOTSTRAP_IF_LESS_THAN_X_PEERS || now - lastBootstrap > DHTConstants.SELF_LOOKUP_INTERVAL) {
				//regualary search for our id to update routing table
				bootstrap();
			} else {
				setStatus(DHTStatus.Running);
			}
		}

		
	}
	
	
	private void resolveBootstrapAddresses() {
		List<InetSocketAddress> nodeAddresses =  new ArrayList<InetSocketAddress>();
		for(int i = 0;i<DHTConstants.BOOTSTRAP_NODES.length;i++)
		{
			try {
				String hostname = DHTConstants.BOOTSTRAP_NODES[i];
				int port = DHTConstants.BOOTSTRAP_PORTS[i];
			

				 for(InetAddress addr : InetAddress.getAllByName(hostname))
				 {
					 nodeAddresses.add(new InetSocketAddress(addr, port));
				 }
			} catch (Exception e) {
				// do nothing
			}
		}
		
		if(nodeAddresses.size() > 0)
			DHTConstants.BOOTSTRAP_NODE_ADDRESSES = nodeAddresses;
	}

	/**
	 * Initiates a Bootstrap.
	 * 
	 * This function bootstraps with router.bittorrent.com if there are less
	 * than 10 Peers in the routing table. If there are more then a lookup on
	 * our own ID is initiated. If the either Task is finished than it will try
	 * to fill the Buckets.
	 */
	public synchronized void bootstrap () {
		if (!isRunning() || bootstrapping || System.currentTimeMillis() - lastBootstrap < DHTConstants.BOOTSTRAP_MIN_INTERVAL) {
			return;
		}
		
		if (useRouterBootstrapping || node.getNumEntriesInRoutingTable() > 1) {
			
			final AtomicInteger finishCount = new AtomicInteger();
			bootstrapping = true;
			
			TaskListener bootstrapListener = t -> {
				int count = finishCount.decrementAndGet();
				if(count == 0)
					bootstrapping = false;
				// fill the remaining buckets once all bootstrap operations finished
				if (count == 0 && running && node.getNumEntriesInRoutingTable() > DHTConstants.USE_BT_ROUTER_IF_LESS_THAN_X_PEERS) {
					node.fillBuckets(DHT.this);
				}
			};

			logInfo("Bootstrapping...");
			lastBootstrap = System.currentTimeMillis();

			for(RPCServer srv : serverManager.getAllServers())
			{
				finishCount.incrementAndGet();
				NodeLookup nl = findNode(srv.getDerivedID(), true, true, srv);
				if (nl == null) {
					bootstrapping = false;
					break;
				} else if (node.getNumEntriesInRoutingTable() < DHTConstants.USE_BT_ROUTER_IF_LESS_THAN_X_PEERS) {
					if (useRouterBootstrapping) {
						resolveBootstrapAddresses();
						List<InetSocketAddress> addrs = new ArrayList<InetSocketAddress>(DHTConstants.BOOTSTRAP_NODE_ADDRESSES);
						Collections.shuffle(addrs);
						
						for (InetSocketAddress addr : addrs)
						{
							if (!type.PREFERRED_ADDRESS_TYPE.isInstance(addr.getAddress()))
								continue;
							nl.addDHTNode(addr.getAddress(),addr.getPort());
							break;
						}
					}
					nl.addListener(bootstrapListener);
					nl.setInfo("Bootstrap: Find Peers.");

					tman.dequeue();

				} else {
					nl.setInfo("Bootstrap: search for ourself.");
					nl.addListener(bootstrapListener);
					tman.dequeue();
				}
				
			}
		}
	}

	private NodeLookup findNode (Key id, boolean isBootstrap,
			boolean isPriority, RPCServer server) {
		if (!running || server == null) {
			return null;
		}

		NodeLookup at = new NodeLookup(id, server, node, isBootstrap);
		tman.addTask(at, isPriority);
		return at;
	}

	/**
	 * Do a NodeLookup.
	 * 
	 * @param id The id of the key to search
	 */
	public NodeLookup findNode (Key id) {
		return findNode(id, false, false,serverManager.getRandomActiveServer(true));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#fillBucket(lbms.plugins.mldht.kad.KBucket)
	 */
	public NodeLookup fillBucket (Key id, KBucket bucket) {
		bucket.updateRefreshTimer();
		return findNode(id, false, true, serverManager.getRandomActiveServer(true));
	}

	public void sendError (MessageBase origMsg, int code, String msg) {
		ErrorMessage errMsg = new ErrorMessage(origMsg.getMTID(), code, msg);
		errMsg.setMethod(origMsg.getMethod());
		errMsg.setDestination(origMsg.getOrigin());
		origMsg.getServer().sendMessage(errMsg);
	}


	public Key getOurID () {
		if (running) {
			return node.getRootID();
		}
		return null;
	}

	private void onStatsUpdate () {
		stats.setNumTasks(tman.getNumTasks() + tman.getNumQueuedTasks());
		stats.setNumPeers(node.getNumEntriesInRoutingTable());
		long numSent = 0;long numReceived = 0;int activeCalls = 0;
		for(RPCServer s : serverManager.getAllServers())
		{
			numSent += s.getNumSent();
			numReceived += s.getNumReceived();
			activeCalls += s.getNumActiveRPCCalls();
		}
		stats.setNumSentPackets(numSent);
		stats.setNumReceivedPackets(numReceived);
		stats.setNumRpcCalls(activeCalls);

		for (int i = 0; i < statsListeners.size(); i++) {
			statsListeners.get(i).statsUpdated(stats);
		}
	}

	private void setStatus (DHTStatus status) {
		if (!this.status.equals(status)) {
			DHTStatus old = this.status;
			this.status = status;
			if (!statusListeners.isEmpty())
			{
				for (int i = 0; i < statusListeners.size(); i++)
				{
					statusListeners.get(i).statusChanged(status, old);
				}
			}
		}
	}

	public void addStatsListener (DHTStatsListener listener) {
		statsListeners.add(listener);
	}

	public void removeStatsListener (DHTStatsListener listener) {
		statsListeners.remove(listener);
	}

	public void addIndexingListener(DHTIndexingListener listener) {
		indexingListeners.add(listener);
	}

	public void addStatusListener (DHTStatusListener listener) {
		statusListeners.add(listener);
	}

	public void removeStatusListener (DHTStatusListener listener) {
		statusListeners.remove(listener);
	}
	
	public void printDiagnostics(PrintWriter w) {
		if(!running)
			return;
		//StringBuilder b = new StringBuilder();

		for(ScheduledFuture<?> f : scheduledActions)
			if(f.isDone())
			{ // check for exceptions
				try
				{
					f.get();
				} catch (ExecutionException | InterruptedException e)
				{
					e.printStackTrace(w);
				}

			}
				
		
		w.println("==========================");
		w.println("DHT Diagnostics. Type "+type);
		w.println("# of active servers / all servers: "+ serverManager.getActiveServerCount()+ '/'+ serverManager.getServerCount());
		
		if(!isRunning())
			return;
		
		w.append("-----------------------\n");
		w.append("Stats\n");
		w.append("Reachable node estimate: "+ estimator.getEstimate()+ " ("+estimator.getStability()+")\n");
		w.append(stats.toString());
		w.append("-----------------------\n");
		w.append("Routing table\n");
		w.append(node.toString());
		w.append("-----------------------\n");
		w.append("RPC Servers\n");
		for(RPCServer srv : serverManager.getAllServers())
			w.append(srv.toString());
		w.append("-----------------------\n");
		w.append("Lookup Cache\n");
		cache.printDiagnostics(w);
		w.append("-----------------------\n");
		w.append("Tasks\n");
		w.append(tman.toString());
		w.append("\n\n\n");
	}

	/**
	 * @return the logger
	 */
	//	public static DHTLogger getLogger () {
	//		return logger;
	//	}
	/**
	 * @param logger the logger to set
	 */
	public static void setLogger (DHTLogger logger) {
		DHT.logger = logger;
	}

	/**
	 * @return the logLevel
	 */
	public static LogLevel getLogLevel () {
		return logLevel;
	}

	/**
	 * @param logLevel the logLevel to set
	 */
	public static void setLogLevel (LogLevel logLevel) {
		DHT.logLevel = logLevel;
		logger.log("Change LogLevel to: " + logLevel, LogLevel.Info);
	}

	/**
	 * @return the scheduler
	 */
	private static ScheduledExecutorService getDefaultScheduler () {
		ScheduledExecutorService service = defaultScheduler;
		if(service == null) {
			initDefaultScheduler();
			service = defaultScheduler;
		}
			
		return service;
	}
	
	private static void initDefaultScheduler() {
		synchronized (DHT.class) {
			if(defaultScheduler == null) {
				executorGroup = new ThreadGroup("mlDHT");
				int threads = Math.max(Runtime.getRuntime().availableProcessors(),2);
				defaultScheduler = new ScheduledThreadPoolExecutor(threads, (ThreadFactory) r -> {
					Thread t = new Thread(executorGroup, r, "mlDHT Scheduler");
					
					t.setUncaughtExceptionHandler((t1, e) -> DHT.log(e, LogLevel.Error));
					t.setDaemon(true);
					return t;
				});
				defaultScheduler.setCorePoolSize(threads);
				defaultScheduler.setMaximumPoolSize(threads*2);
				defaultScheduler.setKeepAliveTime(20, TimeUnit.SECONDS);
				defaultScheduler.allowCoreThreadTimeOut(true);
			}
		}
	}
	

	

	public static void log (String message, LogLevel level) {
		if (level.compareTo(logLevel) < 1) { // <=
			logger.log(message, level);
		}
	}

	public static void log (Throwable e, LogLevel level) {
		if (level.compareTo(logLevel) < 1) { // <=
			logger.log(e, level);
		}
	}

	public static void logFatal (String message) {
		log(message, LogLevel.Fatal);
	}

	public static void logError (String message) {
		log(message, LogLevel.Error);
	}

	public static void logInfo (String message) {
		log(message, LogLevel.Info);
	}

	public static void logDebug (String message) {
		log(message, LogLevel.Debug);
	}

	public static void logVerbose (String message) {
		log(message, LogLevel.Verbose);
	}

	public static boolean isLogLevelEnabled (LogLevel level) {
		return level.compareTo(logLevel) < 1;
	}

	public static enum LogLevel {
		Fatal, Error, Info, Debug, Verbose
	}
}
