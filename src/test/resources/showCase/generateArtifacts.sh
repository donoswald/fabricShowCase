#!/bin/bash +x

CHANNEL_NAME=$1
: ${CHANNEL_NAME:="mychannel"}
echo $CHANNEL_NAME

COMPOSE_TEMPLATE_FILE="docker-compose-2orgs-4peers-template.yaml"
COMPOSE_FILE="docker-compose.yaml"
echo $COMPOSE_FILE

## Using docker-compose template replace private key file names with constants
function replacePrivateKey () {

	cp $COMPOSE_TEMPLATE_FILE $COMPOSE_FILE

        CURRENT_DIR=$PWD
        cd crypto-config/peerOrganizations/org1.example.com/ca/
        PRIV_KEY=$(ls *_sk)
        echo $PRIV_KEY
        cd $CURRENT_DIR
        sed -i "s/CA1_PRIVATE_KEY/${PRIV_KEY}/g" $COMPOSE_FILE
        cd crypto-config/peerOrganizations/org2.example.com/ca/
        PRIV_KEY=$(ls *_sk)
        cd $CURRENT_DIR
        sed -i "s/CA2_PRIVATE_KEY/${PRIV_KEY}/g" $COMPOSE_FILE
}

## Generates Org certs using cryptogen tool
function generateCerts (){

    rm -rf channel-artifacts
    rm -rf crypto-config
    mkdir -p channel-artifacts
	
    CRYPTOGEN=cryptogen
	if [ -f "$CRYPTOGEN" ]; then
            echo "Using cryptogen -> $CRYPTOGEN"
	fi

	echo
	echo "##########################################################"
	echo "##### Generate certificates using cryptogen tool #########"
	echo "##########################################################"
	$CRYPTOGEN generate --config=./crypto-config.yaml
	echo
}

## Generate orderer genesis block , channel configuration transaction and anchor peer update transactions
function generateChannelArtifacts() {

	CONFIGTXGEN=configtxgen
	if [ -f "$CONFIGTXGEN" ]; then
            echo "Using configtxgen -> $CONFIGTXGEN"
	fi

	echo "##########################################################"
	echo "#########  Generating Orderer Genesis block ##############"
	echo "##########################################################"
	# Note: For some unknown reason (at least for now) the block file can't be
	# named orderer.genesis.block or the orderer will fail to launch!
	$CONFIGTXGEN -profile TwoOrgsOrdererGenesis -outputBlock ./channel-artifacts/genesis.block

	echo
	echo "#################################################################"
	echo "### Generating channel configuration transaction 'channel.tx' ###"
	echo "#################################################################"
	$CONFIGTXGEN -profile TwoOrgsChannel -outputCreateChannelTx ./channel-artifacts/${CHANNEL_NAME}.tx -channelID $CHANNEL_NAME

	echo
	echo "#################################################################"
	echo "#######    Generating anchor peer update for Org1MSP   ##########"
	echo "#################################################################"
	$CONFIGTXGEN -profile TwoOrgsChannel -outputAnchorPeersUpdate ./channel-artifacts/Org1MSPanchors.tx -channelID $CHANNEL_NAME -asOrg Org1MSP

	echo
	echo "#################################################################"
	echo "#######    Generating anchor peer update for Org2MSP   ##########"
	echo "#################################################################"
	$CONFIGTXGEN -profile TwoOrgsChannel -outputAnchorPeersUpdate ./channel-artifacts/Org2MSPanchors.tx -channelID $CHANNEL_NAME -asOrg Org2MSP
	echo
}

generateCerts
replacePrivateKey
generateChannelArtifacts

