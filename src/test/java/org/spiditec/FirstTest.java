package org.spiditec;

import static java.lang.String.format;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.hyperledger.fabric.sdk.Channel;
import org.hyperledger.fabric.sdk.ChannelConfiguration;
import org.hyperledger.fabric.sdk.EventHub;
import org.hyperledger.fabric.sdk.HFClient;
import org.hyperledger.fabric.sdk.Orderer;
import org.hyperledger.fabric.sdk.Peer;
import org.hyperledger.fabric.sdk.security.CryptoSuite;
import org.hyperledger.fabric_ca.sdk.HFCAClient;
import org.hyperledger.fabric_ca.sdk.HFCAInfo;
import org.hyperledger.fabric_ca.sdk.RegistrationRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class FirstTest {
  private final TestConfigHelper configHelper = new TestConfigHelper();
  private static final TestConfig testConfig = TestConfig.getConfig("src/test/resources/yeasy/channel-artifacts",
      "src/test/resources/yeasy/crypto-config");
  private static final String CHANNEL_NAME = "mychannel";
  private static final String TEST_ADMIN_NAME = "admin";
  private static final String TESTUSER_1_NAME = "user1";
  private Collection<SampleOrg> testSampleOrgs;

  @Before
  public void setUp() throws Exception {
    configHelper.clearConfig();
    configHelper.customizeConfig();

    testSampleOrgs = testConfig.getIntegrationTestsSampleOrgs();

    for (SampleOrg sampleOrg : testSampleOrgs) {
      String caName = sampleOrg.getCAName(); // Try one of each name and no
                                             // name.
      if (caName != null && !caName.isEmpty()) {
        sampleOrg
            .setCAClient(HFCAClient.createNewInstance(caName, sampleOrg.getCALocation(), sampleOrg.getCAProperties()));
      } else {
        sampleOrg.setCAClient(HFCAClient.createNewInstance(sampleOrg.getCALocation(), sampleOrg.getCAProperties()));
      }
    }

    File sampleStoreFile = new File(System.getProperty("java.io.tmpdir") + "/HFCSampletest.properties");
    if (sampleStoreFile.exists()) { // For testing start fresh
      sampleStoreFile.delete();
    }

    final SampleStore sampleStore = new SampleStore(sampleStoreFile);

    for (SampleOrg sampleOrg : testSampleOrgs) {

      HFCAClient ca = sampleOrg.getCAClient();

      final String orgName = sampleOrg.getName();
      final String mspid = sampleOrg.getMSPID();
      ca.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());

      HFCAInfo info = ca.info(); // just check if we connect at all.
      assertNotNull(info);
      String infoName = info.getCAName();
      if (infoName != null && !infoName.isEmpty()) {
        assertEquals(ca.getCAName(), infoName);
      }

      SampleUser admin = sampleStore.getMember(TEST_ADMIN_NAME, orgName);
      if (!admin.isEnrolled()) {
        admin.setEnrollment(ca.enroll(admin.getName(), "adminpw"));
        admin.setMspId(mspid);
      }

      sampleOrg.setAdmin(admin); // The admin of this org --

      final String sampleOrgName = sampleOrg.getName();
      final String sampleOrgDomainName = sampleOrg.getDomainName();

      SampleUser peerOrgAdmin = sampleStore.getMember(sampleOrgName + "Admin", sampleOrgName, sampleOrg.getMSPID(),
          Util.findFileSk(Paths.get(testConfig.getTestCryptoConfigPath(), "peerOrganizations/",
              sampleOrgDomainName, format("/users/Admin@%s/msp/keystore", sampleOrgDomainName)).toFile()),
          Paths
              .get(testConfig.getTestCryptoConfigPath(), "peerOrganizations/", sampleOrgDomainName,
                  format("/users/Admin@%s/msp/signcerts/Admin@%s-cert.pem", sampleOrgDomainName, sampleOrgDomainName))
              .toFile());

      sampleOrg.setPeerAdmin(peerOrgAdmin); 

    }
  }

  @Test
  public void test() throws Exception {
    HFClient client = HFClient.createNewInstance();

    client.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());

    SampleOrg sampleOrg = testConfig.getIntegrationTestsSampleOrg("peerOrg1");
    Channel fooChannel = constructChannel(CHANNEL_NAME, client, sampleOrg);
    fooChannel.shutdown(true);

  }

  private Channel constructChannel(String name, HFClient client, SampleOrg sampleOrg) throws Exception {

    client.setUserContext(sampleOrg.getPeerAdmin());

    Collection<Orderer> orderers = new LinkedList<>();

    for (String orderName : sampleOrg.getOrdererNames()) {

      Properties ordererProperties = testConfig.getOrdererProperties(orderName);

      ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveTime", new Object[] { 5L, TimeUnit.MINUTES });
      ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveTimeout", new Object[] { 8L, TimeUnit.SECONDS });

      orderers.add(client.newOrderer(orderName, sampleOrg.getOrdererLocation(orderName), ordererProperties));
    }
    Orderer anOrderer = orderers.iterator().next();
    orderers.remove(anOrderer);

    ChannelConfiguration channelConfiguration = new ChannelConfiguration(
        new File(testConfig.getTestChannelPath(), name + ".tx"));

    // Create channel that has only one signer that is this orgs peer admin. If
    // channel creation policy needed more signature they would need to be added
    // too.
    Channel newChannel = client.newChannel(name, anOrderer, channelConfiguration,
        client.getChannelConfigurationSignature(channelConfiguration, sampleOrg.getPeerAdmin()));


    for (String peerName : sampleOrg.getPeerNames()) {
      String peerLocation = sampleOrg.getPeerLocation(peerName);

      Properties peerProperties = testConfig.getPeerProperties(peerName); 
      if (peerProperties == null) {
        peerProperties = new Properties();
      }
      // Example of setting specific options on grpc's NettyChannelBuilder
      peerProperties.put("grpc.NettyChannelBuilderOption.maxInboundMessageSize", 9000000);

      Peer peer = client.newPeer(peerName, peerLocation, peerProperties);
      newChannel.joinPeer(peer);
      sampleOrg.addPeer(peer);
    }

    for (Orderer orderer : orderers) { // add remaining orderers if any.
      newChannel.addOrderer(orderer);
    }

    for (String eventHubName : sampleOrg.getEventHubNames()) {

      final Properties eventHubProperties = testConfig.getEventHubProperties(eventHubName);

      eventHubProperties.put("grpc.NettyChannelBuilderOption.keepAliveTime", new Object[] { 5L, TimeUnit.MINUTES });
      eventHubProperties.put("grpc.NettyChannelBuilderOption.keepAliveTimeout", new Object[] { 8L, TimeUnit.SECONDS });

      EventHub eventHub = client.newEventHub(eventHubName, sampleOrg.getEventHubLocation(eventHubName),
          eventHubProperties);
      newChannel.addEventHub(eventHub);
    }

    newChannel.initialize();

    return newChannel;
  }

  @After
  public void tearDown() throws Exception {
    configHelper.clearConfig();
  }

}
