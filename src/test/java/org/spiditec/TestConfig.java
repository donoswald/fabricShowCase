/*
 *  Copyright 2016, 2017 IBM, DTCC, Fujitsu Australia Software Technology, IBM - All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *        http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.spiditec;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperledger.fabric.sdk.helper.Utils;


/**
 * Config allows for a global config of the toolkit. Central location for all
 * toolkit configuration defaults. Has a local config file that can override any
 * property defaults. Config file can be relocated via a system property
 * "org.hyperledger.fabric.sdk.configuration". Any property can be overridden
 * with environment variable and then overridden
 * with a java system property. Property hierarchy goes System property
 * overrides environment variable which overrides config file for default values specified here.
 */

/**
 * Test Configuration
 */

public class TestConfig {
  private static final Log logger = LogFactory.getLog(TestConfig.class);

  private static final String PROPBASE = "org.hyperledger.fabric.sdktest.";

  private static final String INVOKEWAITTIME = PROPBASE + "InvokeWaitTime";
  private static final String DEPLOYWAITTIME = PROPBASE + "DeployWaitTime";
  private static final String PROPOSALWAITTIME = PROPBASE + "ProposalWaitTime";

  private static final String INTEGRATIONTESTS_ORG = PROPBASE + "integrationTests.org.";
  private static final Pattern orgPat = Pattern
      .compile("^" + Pattern.quote(INTEGRATIONTESTS_ORG) + "([^\\.]+)\\.mspid$");

  private static final String INTEGRATIONTESTSTLS = PROPBASE + "integrationtests.tls";

  private static TestConfig config;
  private static final Properties sdkProperties = new Properties();
  private final boolean runningTLS;
  private final boolean runningFabricCATLS;
  private final boolean runningFabricTLS;

  private final String channelArtifactsPath;
  private final String cryptoConfigPath;
  private static final HashMap<String, SampleOrg> sampleOrgs = new HashMap<>();

  private TestConfig(String channelArtifactsPath,String cryptoConfigPath) {
     this.channelArtifactsPath=channelArtifactsPath;
     this.cryptoConfigPath  = cryptoConfigPath;
    // Default values

    defaultProperty(INVOKEWAITTIME, "100000");
    defaultProperty(DEPLOYWAITTIME, "120000");
    defaultProperty(PROPOSALWAITTIME, "120000");

    //////
    defaultProperty(INTEGRATIONTESTS_ORG + "peerOrg1.mspid", "Org1MSP");
    defaultProperty(INTEGRATIONTESTS_ORG + "peerOrg1.domname", "org1.example.com");
    defaultProperty(INTEGRATIONTESTS_ORG + "peerOrg1.ca_location", "http://localhost:7054");
    defaultProperty(INTEGRATIONTESTS_ORG + "peerOrg1.caName", "ca0");
    defaultProperty(INTEGRATIONTESTS_ORG + "peerOrg1.peer_locations", "peer0.org1.example.com@grpc://localhost:7051, peer1.org1.example.com@grpc://localhost:7056");
    defaultProperty(INTEGRATIONTESTS_ORG + "peerOrg1.orderer_locations", "orderer.example.com@grpc://localhost:7050");
    defaultProperty(INTEGRATIONTESTS_ORG + "peerOrg1.eventhub_locations","peer0.org1.example.com@grpc://localhost:7053,peer1.org1.example.com@grpc://localhost:7058");

    defaultProperty(INTEGRATIONTESTS_ORG + "peerOrg2.mspid", "Org2MSP");
    defaultProperty(INTEGRATIONTESTS_ORG + "peerOrg2.domname", "org2.example.com");
    defaultProperty(INTEGRATIONTESTS_ORG + "peerOrg2.ca_location", "http://localhost:8054");
    defaultProperty(INTEGRATIONTESTS_ORG + "peerOrg2.caName", "ca1");
    defaultProperty(INTEGRATIONTESTS_ORG + "peerOrg2.peer_locations","peer0.org2.example.com@grpc://localhost:8051,peer1.org2.example.com@grpc://localhost:8056");
    defaultProperty(INTEGRATIONTESTS_ORG + "peerOrg2.orderer_locations", "orderer.example.com@grpc://localhost:7050");
    defaultProperty(INTEGRATIONTESTS_ORG + "peerOrg2.eventhub_locations","peer0.org2.example.com@grpc://localhost:8053, peer1.org2.example.com@grpc://localhost:8058");

    defaultProperty(INTEGRATIONTESTSTLS, null);
    runningTLS = null != sdkProperties.getProperty(INTEGRATIONTESTSTLS, null);
    runningFabricCATLS = runningTLS;
    runningFabricTLS = runningTLS;

    for (Map.Entry<Object, Object> x : sdkProperties.entrySet()) {
      final String key = x.getKey() + "";
      final String val = x.getValue() + "";

      if (key.startsWith(INTEGRATIONTESTS_ORG)) {

        Matcher match = orgPat.matcher(key);

        if (match.matches() && match.groupCount() == 1) {
          String orgName = match.group(1).trim();
          sampleOrgs.put(orgName, new SampleOrg(orgName, val.trim()));

        }
      }
    }

    for (Map.Entry<String, SampleOrg> org : sampleOrgs.entrySet()) {
      final SampleOrg sampleOrg = org.getValue();
      final String orgName = org.getKey();

      String peerNames = sdkProperties.getProperty(INTEGRATIONTESTS_ORG + orgName + ".peer_locations");
      String[] ps = peerNames.split("[ \t]*,[ \t]*");
      for (String peer : ps) {
        String[] nl = peer.split("[ \t]*@[ \t]*");
        sampleOrg.addPeerLocation(nl[0], grpcTLSify(nl[1]));
      }

      final String domainName = sdkProperties.getProperty(INTEGRATIONTESTS_ORG + orgName + ".domname");

      sampleOrg.setDomainName(domainName);

      String ordererNames = sdkProperties.getProperty(INTEGRATIONTESTS_ORG + orgName + ".orderer_locations");
      ps = ordererNames.split("[ \t]*,[ \t]*");
      for (String peer : ps) {
        String[] nl = peer.split("[ \t]*@[ \t]*");
        sampleOrg.addOrdererLocation(nl[0], grpcTLSify(nl[1]));
      }

      String eventHubNames = sdkProperties.getProperty(INTEGRATIONTESTS_ORG + orgName + ".eventhub_locations");
      ps = eventHubNames.split("[ \t]*,[ \t]*");
      for (String peer : ps) {
        String[] nl = peer.split("[ \t]*@[ \t]*");
        sampleOrg.addEventHubLocation(nl[0], grpcTLSify(nl[1]));
      }

      sampleOrg
          .setCALocation(httpTLSify(sdkProperties.getProperty((INTEGRATIONTESTS_ORG + org.getKey() + ".ca_location"))));

      sampleOrg.setCAName(sdkProperties.getProperty((INTEGRATIONTESTS_ORG + org.getKey() + ".caName")));

    }

  }

  private String grpcTLSify(String location) {
    location = location.trim();
    Exception e = Utils.checkGrpcUrl(location);
    if (e != null) {
      throw new RuntimeException(String.format("Bad TEST parameters for grpc url %s", location), e);
    }
    return runningFabricTLS ? location.replaceFirst("^grpc://", "grpcs://") : location;

  }

  private String httpTLSify(String location) {
    location = location.trim();

    return runningFabricCATLS ? location.replaceFirst("^http://", "https://") : location;
  }

  /**
   * getConfig return back singleton for SDK configuration.
   *
   * @return Global configuration
   */
  public static TestConfig getConfig(String channelArtifactsPath,String cryptoConfigPath) {
    if (null == config) {
      config = new TestConfig(channelArtifactsPath,cryptoConfigPath);
    }
    return config;

  }

  /**
   * getProperty return back property for the given value.
   *
   * @param property
   * @return String value for the property
   */
  private String getProperty(String property) {

    String ret = sdkProperties.getProperty(property);

    if (null == ret) {
      logger.warn(String.format("No configuration value found for '%s'", property));
    }
    return ret;
  }

  private static void defaultProperty(String key, String value) {

    String ret = System.getProperty(key);
    if (ret != null) {
      sdkProperties.put(key, ret);
    } else {
      String envKey = key.toUpperCase().replaceAll("\\.", "_");
      ret = System.getenv(envKey);
      if (null != ret) {
        sdkProperties.put(key, ret);
      } else {
        if (null == sdkProperties.getProperty(key) && value != null) {
          sdkProperties.put(key, value);
        }

      }

    }
  }

  public int getTransactionWaitTime() {
    return Integer.parseInt(getProperty(INVOKEWAITTIME));
  }

  public int getDeployWaitTime() {
    return Integer.parseInt(getProperty(DEPLOYWAITTIME));
  }

  public long getProposalWaitTime() {
    return Integer.parseInt(getProperty(PROPOSALWAITTIME));
  }

  public Collection<SampleOrg> getIntegrationTestsSampleOrgs() {
    return Collections.unmodifiableCollection(sampleOrgs.values());
  }

  public SampleOrg getIntegrationTestsSampleOrg(String name) {
    return sampleOrgs.get(name);

  }

  public Properties getPeerProperties(String name) {

    return getEndPointProperties("peer", name);

  }

  public Properties getOrdererProperties(String name) {

    return getEndPointProperties("orderer", name);

  }

  private Properties getEndPointProperties(final String type, final String name) {

    final String domainName = getDomainName(name);

    File cert = Paths.get(getTestCryptoConfigPath(), "ordererOrganizations".replace("orderer", type),
        domainName, type + "s", name, "tls/server.crt").toFile();
    if (!cert.exists()) {
      throw new RuntimeException(
          String.format("Missing cert file for: %s. Could not find at location: %s", name, cert.getAbsolutePath()));
    }

    Properties ret = new Properties();
    ret.setProperty("pemFile", cert.getAbsolutePath());
    ret.setProperty("trustServerCertificate", "true"); // testing environment
                                                       // only NOT FOR
                                                       // PRODUCTION!
    ret.setProperty("hostnameOverride", name);
    ret.setProperty("sslProvider", "openSSL");
    ret.setProperty("negotiationType", "TLS");

    return ret;
  }

  public Properties getEventHubProperties(String name) {

    return getEndPointProperties("peer", name); // uses same as named peer

  }

  public String getTestChannelPath() {

    return channelArtifactsPath;

  }
  
  public String getTestCryptoConfigPath() {
    return cryptoConfigPath;
  }

  private String getDomainName(final String name) {
    int dot = name.indexOf(".");
    if (-1 == dot) {
      return null;
    } else {
      return name.substring(dot + 1);
    }

  }

}
