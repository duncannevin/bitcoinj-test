import java.io.File

import com.google.common.base.Preconditions.checkNotNull
import com.google.common.util.concurrent.{FutureCallback, Futures, MoreExecutors}
import org.bitcoinj.core._
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.{MainNetParams, RegTestParams, TestNet3Params}
import org.bitcoinj.utils.BriefLogFormatter
import org.bitcoinj.wallet.{SendRequest, Wallet}

object Main {
  def main(args: Array[String]): Unit = {
    object tags {
      final val forwardingService = "forwarding-service"
      final val regtest = "regtest"
      final val testnet = "testnet"
    }

    // make logs more compact
    BriefLogFormatter.init()
    if (args.length < 2) {
      println(s"Usage: address-to-send-back-to [${tags.regtest}|${tags.testnet}]")
      return
    }

    // figure out which network is to be used
    val (params, filePrefix): (NetworkParameters, String) = args(1) match {
      case "testnet" => (TestNet3Params.get(), s"${tags.forwardingService}-${tags.testnet}")
      case "regtest" => (RegTestParams.get(), s"${tags.forwardingService}-${tags.regtest}")
      case _ => (MainNetParams.get(), s"${tags.forwardingService}")
    }

    // parse the address given as the first argument
    val forwardingAddress: LegacyAddress = LegacyAddress.fromBase58(params, args.head)

    // start up a basic app using a class that automates some boilerplate. Ensure we always have at least one key
    val kit: WalletAppKit = new WalletAppKit(params, new File("."), filePrefix) {
      override def onSetupCompleted(): Unit = if (wallet().getKeyChainGroupSize < 1) wallet().importKey(new ECKey())
    }

    def forwardCoins(transaction: Transaction): Unit = {
      try {
        // now send the coins onwards
        val sendRequest: SendRequest = SendRequest.emptyWallet(forwardingAddress)
        val sendResult: Wallet.SendResult = kit.wallet().sendCoins(sendRequest)
        checkNotNull(sendResult)
        println("Sending ...")
        // register a callback that is invoked when the transaction has propagated across the network.
        // This shows a second style of registering ListenableFuture callbacks, it works when you don't
        // need access to the object the future returns
        sendResult.broadcastComplete.addListener(() => {
          println(s"Sent coins onwards! Transaction hash is ${sendRequest.tx.getTxId}")
        }, MoreExecutors.directExecutor())
      } catch {
        case keyCrypterException: KeyCrypterException => new RuntimeException(keyCrypterException)
        case insufficientMoneyException: InsufficientMoneyException => new RuntimeException(insufficientMoneyException)
      }
    }

    // if in regtest mode connect to localhost
    if (params == RegTestParams.get()) kit.connectToLocalHost()

    // download the block chain and wait until it's done
    kit.startAsync()
    kit.awaitRunning()

    // listens for transactions
    kit.wallet().addCoinsReceivedEventListener((wallet: Wallet, tx: Transaction, prevBalance: Coin, newBalance: Coin) => {
      // runs in the dedicated "user thread"
      //
      // the transaction "tx" can either be pending, or included into a block (we didn't see the broadcast)
      val value: Coin = tx.getValueSentToMe(wallet)
      println(s"Received tx for ${value.toFriendlyString}: $tx")
      println("Transaction will be forwarded after it confirms.")

      // wait until it's made it into the block chain
      //
      // For this dummy app of course, we could just forward the unconfirmed transaction. If it were
      // to be double spent, no harm done. Wallet.allowSpendingUnconfirmedTransactions() would have to
      // be called in onSetupCompleted() above. But we don't do that here to demonstrate the more common
      // case of waiting for a block.
      Futures.addCallback(tx.getConfidence().getDepthFuture(1), new FutureCallback[TransactionConfidence]() {
        override def onSuccess(result: TransactionConfidence): Unit = forwardCoins(tx)
        override def onFailure(t: Throwable): Unit = throw new RuntimeException(t)
      }, MoreExecutors.directExecutor())
    })

    val sendToAddress = LegacyAddress.fromKey(params, kit.wallet().currentReceiveKey())
    println(s"Send coins to: $sendToAddress")
    println("Waiting for coins to arrive. Press Ctrl-C to quit.")

    try
      Thread.sleep(Long.MaxValue)
    catch {
      case _: InterruptedException => ???
    }

  }
}
