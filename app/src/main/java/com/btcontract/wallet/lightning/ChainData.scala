package com.btcontract.wallet.lightning

import org.bitcoinj.core._
import rx.lang.scala.{Observable => Obs, Subscription}
import com.btcontract.wallet.helper.{Tx, RichCursor, Insight}
import scala.concurrent.duration.DurationInt
import com.btcontract.wallet.Utils.app
import org.bitcoinj.core.Utils.HEX
import lncloud.Commits
import org.bitcoinj


object ChainData {
  def watchTxDepthLocal(watchTxId: String) = Obs.create[Int] { obs =>
    val lst = new bitcoinj.core.listeners.TransactionConfidenceEventListener {
      def onTransactionConfidenceChanged(w: bitcoinj.wallet.Wallet, tx: Transaction) =
        if (tx.getHashAsString == watchTxId) obs onNext tx.getConfidence.getDepthInBlocks
    }

    app.kit.wallet addTransactionConfidenceEventListener lst
    Subscription(app.kit.wallet removeTransactionConfidenceEventListener lst)
  }

  // When anchor is launched we need to constantly check if it's confirmed yet
  def watchTxDepthRemote(c: Commitments) = Insight.txs(app.getTo(c.anchorOutput).toString)
    .filter(_.txid == c.anchorId).map(_.confirmations).repeatWhen(_ delay 2.minute)

  // When channel is active we can't solely rely on server so we check anchor from time to time
  def watchAnchorSpentRemote(c: Commitments) = Insight.txs(app.getTo(c.anchorOutput).toString)
    .filter(tx => tx.vin.exists(_.txid == c.anchorId) && tx.confirmations > 0)
    .map(_.txid).repeatWhen(_ delay 20.minute)

  // Punishment for revoked commit tx
  def saveTx(id: String, tx: Transaction) = util.Try {
    val serializedPunishTx = HEX encode tx.bitcoinSerialize
    app.LNData.db.change(Commits.newSql, id, serializedPunishTx)
  }

  def getTx(parentCommitTxId: String) = {
    def hex2Tx(hex: String) = new Transaction(app.params, HEX decode hex)
    val cursor = app.LNData.db.select(Commits.selectByParentTxIdSql, parentCommitTxId)
    RichCursor(cursor).closeAfter(_.toStream.headOption.map(_ string Commits.punishTx) map hex2Tx)
  }
}