/*
 * Copyright 2013 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package voldemort.utils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;

import org.apache.commons.codec.DecoderException;
import org.apache.log4j.Logger;

import voldemort.client.protocol.admin.AdminClient;
import voldemort.client.protocol.admin.AdminClientConfig;
import voldemort.cluster.Cluster;
import voldemort.store.StoreDefinition;
import voldemort.versioning.Versioned;

/**
 * The KeyVersionSamplerCLI is a rudimentary tool that outputs a sampling of
 * existing keys from a cluster. For each store in the cluster, a distinct file
 * of keys to sample is expected. And, for each of these, a distint file of
 * key-versions is generated.
 * 
 */
public class KeyVersionSamplerCLI {

    private static Logger logger = Logger.getLogger(ConsistencyCheck.class);

    private final static int KEY_PARALLELISM = 4;

    private final AdminClient adminClient;
    private final Cluster cluster;
    private final List<StoreDefinition> storeDefinitions;
    private final Map<String, StringBuilder> storeNameToKeyStringsMap;

    private final String inDir;
    private final String outDir;

    private final ExecutorService kvSamplerService;

    public KeyVersionSamplerCLI(String url, String inDir, String outDir, int keyParallelism) {
        if(logger.isInfoEnabled()) {
            logger.info("Connecting to bootstrap server: " + url);
        }
        this.adminClient = new AdminClient(url, new AdminClientConfig());
        this.cluster = adminClient.getAdminClientCluster();
        this.storeDefinitions = adminClient.metadataMgmtOps.getRemoteStoreDefList(0).getValue();
        this.storeNameToKeyStringsMap = new HashMap<String, StringBuilder>();
        for(StoreDefinition storeDefinition: storeDefinitions) {
            this.storeNameToKeyStringsMap.put(storeDefinition.getName(), new StringBuilder());
        }

        this.inDir = inDir;
        this.outDir = outDir;

        this.kvSamplerService = Executors.newFixedThreadPool(keyParallelism);
    }

    public boolean sampleStores() {
        for(StoreDefinition storeDefinition: storeDefinitions) {
            if(!sampleStore(storeDefinition)) {
                return false;
            }
        }
        return true;
    }

    public class KeyVersionSampler implements Callable<String> {

        private final StoreInstance storeInstance;
        private final byte[] key;

        KeyVersionSampler(StoreInstance storeInstance, byte[] key) {
            this.storeInstance = storeInstance;
            this.key = key;
        }

        @Override
        public String call() throws Exception {
            String storeName = storeInstance.getStoreDefinition().getName();
            int masterPartitionId = storeInstance.getMasterPartitionId(key);
            List<Integer> replicatingNodeIds = storeInstance.getReplicationNodeList(masterPartitionId);

            int replicationOffset = 0;
            StringBuilder sb = new StringBuilder();
            for(int replicatingNodeId: replicatingNodeIds) {
                List<Versioned<byte[]>> values = adminClient.storeOps.getNodeKey(storeName,
                                                                                 replicatingNodeId,
                                                                                 new ByteArray(key));
                sb.append(replicationOffset + " : " + ByteUtils.toHexString(key) + "\t");
                for(Versioned<byte[]> value: values) {
                    sb.append(value.getVersion().toString() + "\t");
                }
                sb.append("\n");
                replicationOffset++;
            }
            return sb.toString();
        }
    }

    public boolean sampleStore(StoreDefinition storeDefinition) {
        String storeName = storeDefinition.getName();

        String keysFileName = inDir + System.getProperty("file.separator") + storeName + ".keys";
        File keysFile = new File(keysFileName);
        if(!keysFile.exists()) {
            logger.error("Keys file " + keysFileName + "does not exist!");
            return false;
        }

        String kvFileName = outDir + System.getProperty("file.separator") + storeName + ".kvs";
        File kvFile = new File(kvFileName);
        if(kvFile.exists()) {
            logger.info("Key-Version file " + kvFileName
                        + " exists, so will not sample keys from file " + keysFileName + ".");
            return true;
        }

        StoreInstance storeInstance = new StoreInstance(cluster, storeDefinition);
        BufferedReader keyReader = null;
        BufferedWriter kvWriter = null;
        try {
            keyReader = new BufferedReader(new FileReader(keysFileName));

            Queue<Future<String>> futureKVs = new LinkedList<Future<String>>();
            for(String keyLine = keyReader.readLine(); keyLine != null; keyLine = keyReader.readLine()) {
                byte[] keyInBytes = ByteUtils.fromHexString(keyLine.trim());

                KeyVersionSampler kvSampler = new KeyVersionSampler(storeInstance, keyInBytes);
                Future<String> future = kvSamplerService.submit(kvSampler);
                futureKVs.add(future);
            }

            kvWriter = new BufferedWriter(new FileWriter(kvFileName));
            while(!futureKVs.isEmpty()) {
                Future<String> future = futureKVs.poll();
                String keyVersions = future.get();
                kvWriter.append(keyVersions);
            }

            return true;
        } catch(DecoderException de) {
            logger.error("Could not decode key to sample for store " + storeName + " : "
                         + de.getMessage());
            return false;
        } catch(IOException ioe) {
            logger.error("IOException caught while sampling store " + storeName + " : "
                         + ioe.getMessage());
            return false;
        } catch(InterruptedException ie) {
            logger.error("InterruptedException caught while sampling store " + storeName + " : "
                         + ie.getMessage());
            return false;
        } catch(ExecutionException ee) {
            logger.error("Encountered an execution exception while sampling " + storeName + ": "
                         + ee.getMessage());
            ee.printStackTrace();
            return false;
        } finally {
            if(keyReader != null) {
                try {
                    keyReader.close();
                } catch(IOException e) {
                    logger.error("IOException caught while trying to close keyReader for store "
                                 + storeName + " : " + e.getMessage());
                    e.printStackTrace();
                }
            }
            if(kvWriter != null) {
                try {
                    kvWriter.close();
                } catch(IOException e) {
                    logger.error("IOException caught while trying to close kvWriter for store "
                                 + storeName + " : " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    public void stop() {
        if(adminClient != null) {
            adminClient.stop();
        }
        kvSamplerService.shutdown();
    }

    /**
     * Return args parser
     * 
     * @return program parser
     * */
    private static OptionParser getParser() {
        OptionParser parser = new OptionParser();
        parser.accepts("help", "print help information");
        parser.accepts("url", "[REQUIRED] bootstrap URL")
              .withRequiredArg()
              .describedAs("bootstrap-url")
              .ofType(String.class);
        parser.accepts("in-dir",
                       "[REQUIRED] Directory in which to find the input key files (named \"{storeName}.kvs\", generated by KeySamplerCLI.")
              .withRequiredArg()
              .describedAs("inputDirectory")
              .ofType(String.class);
        parser.accepts("out-dir",
                       "[REQUIRED] Directory in which to output the key files (named \"{storeName}.kvs\".")
              .withRequiredArg()
              .describedAs("outputDirectory")
              .ofType(String.class);
        parser.accepts("parallelism",
                       "Number of key-versions to sample in parallel. [Default: " + KEY_PARALLELISM
                               + " ]")
              .withRequiredArg()
              .describedAs("storeParallelism")
              .ofType(Integer.class);
        return parser;
    }

    /**
     * Print Usage to STDOUT
     */
    private static void printUsage() {
        StringBuilder help = new StringBuilder();
        help.append("KeySamplerCLI Tool\n");
        help.append("  Find one key from each store-partition. Output keys per store.\n");
        help.append("Options:\n");
        help.append("  Required:\n");
        help.append("    --url <bootstrap-url>\n");
        help.append("    --in-dir <inputDirectory>\n");
        help.append("    --out-dir <outputDirectory>\n");
        help.append("  Optional:\n");
        help.append("    --parallelism <keyParallelism>\n");
        help.append("    --help\n");
        System.out.print(help.toString());
    }

    private static void printUsageAndDie(String errMessage) {
        printUsage();
        Utils.croak("\n" + errMessage);
    }

    public static void main(String[] args) throws Exception {
        OptionSet options = null;
        try {
            options = getParser().parse(args);
        } catch(OptionException oe) {
            printUsageAndDie("Exception when parsing arguments : " + oe.getMessage());
            return;
        }

        /* validate options */
        if(options.hasArgument("help")) {
            printUsage();
            return;
        }
        if(!options.hasArgument("url") || !options.hasArgument("in-dir")
           || !options.hasArgument("out-dir")) {
            printUsageAndDie("Missing a required argument.");
            return;
        }

        String url = (String) options.valueOf("url");

        String inDir = (String) options.valueOf("in-dir");
        Utils.mkdirs(new File(inDir));

        String outDir = (String) options.valueOf("out-dir");
        Utils.mkdirs(new File(outDir));

        Integer keyParallelism = KEY_PARALLELISM;
        if(options.hasArgument("parallelism")) {
            keyParallelism = (Integer) options.valueOf("parallelism");
        }

        try {
            KeyVersionSamplerCLI sampler = new KeyVersionSamplerCLI(url,
                                                                    inDir,
                                                                    outDir,
                                                                    keyParallelism);

            try {
                if(!sampler.sampleStores()) {
                    logger.error("Key-versions were not successfully sampled from some stores.");
                }
            } finally {
                sampler.stop();
            }

        } catch(Exception e) {
            Utils.croak("Exception during key-version sampling: " + e.getMessage());
        }

    }
}
