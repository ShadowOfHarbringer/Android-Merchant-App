package com.bitcoin.merchant.app.util

import android.util.Log
import com.bitcoin.merchant.app.application.CashRegisterApplication
import com.bitcoin.merchant.app.database.DBControllerV3
import com.github.kiulian.converter.b58.B58
import org.bitcoinj.core.AddressFormatException
import org.bitcoinj.core.Base58
import org.bitcoinj.core.ECKey
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.params.MainNetParams
import org.json.JSONObject
import java.net.URL
import java.nio.ByteBuffer
import java.util.*

class WalletUtil(private val xPub: String, private val app: CashRegisterApplication) {
    private val accountKey: DeterministicKey
    // For performance reasons, we cache all used addresses (reported in May 2019 on Lenovo Tab E8)
    private val addressBank: AddressBank
    private var xpubIndex: Int
    fun isSameXPub(xPub: String): Boolean {
        val b1 = B58.decodeAndCheck(this.xPub)
        val b2 = B58.decodeAndCheck(xPub)
        return Arrays.equals(b1, b2)
    }

    fun addUsedAddress(address: String) {
        addressBank.addUsedAddress(address.trim { it <= ' ' })
    }

    private fun saveWallet(newIndex: Int) {
        Settings.setXPubIndex(app, xPub, newIndex);
        Log.d(TAG, "Saving new xpub index $newIndex")
    }

    fun generateAddressFromXPub(): String {
        return loopThroughXpubChildren()
    }

    fun syncXpub(): Boolean {
        loopThroughXpubChildren()
        return true
    }

    private fun loopThroughXpubChildren(): String {
        var potentialAddress = getAddressFromXpubKey(xpubIndex)
        while (true) {
            potentialAddress = if (addressBank.isUsed(potentialAddress)) {
                xpubIndex++
                Log.d(TAG, "Getting next xpub index " + xpubIndex)
                saveWallet(xpubIndex)
                getAddressFromXpubKey(xpubIndex)
            } else {
                val hasHistory = doesAddressHaveHistory(potentialAddress)
                if (hasHistory) {
                    xpubIndex++
                    Log.d(TAG, "Getting next xpub index " + xpubIndex)
                    saveWallet(xpubIndex)
                    addUsedAddress(potentialAddress)
                    getAddressFromXpubKey(xpubIndex)
                } else {
                    break
                }
            }
        }
        saveWallet(xpubIndex)
        return potentialAddress
    }

    private fun doesAddressHaveHistory(address: String): Boolean {
        var doubleBackOff: Long = 1000
        val maxRetry = 9
        for (i in 0 until maxRetry) {
            doubleBackOff *= try {
                val out = Scanner(URL("https://rest.bitcoin.com/v2/address/details/$address").openStream(), "UTF-8").useDelimiter("\\A").next()
                val json = JSONObject(out)
                return json.getJSONArray("transactions").length() > 0
            } catch (e: Exception) {
                Log.e(TAG, "doesAddressHaveHistory", e)
                try {
                    Thread.sleep(doubleBackOff)
                } catch (ex: InterruptedException) { // fail silently
                }
                2
            }
        }
        return false
    }

    private fun getAddressFromXpubKey(index: Int): String { //This takes the accountKey from earlier, on the receive chain, and generates an address. For example, m/44'/145'/0'/0/{index}
        val dk = HDKeyDerivation.deriveChildKey(accountKey, ChildNumber(index, false))
        val ecKey = ECKey.fromPublicOnly(dk.pubKey)
        return ecKey.toAddress(MainNetParams.get()).toBase58()
    }

    override fun toString(): String {
        return "WalletUtil{" +
                "xPub='" + xPub + '\'' +
                ", index=" + xpubIndex +
                '}'
    }

    private inner class AddressBank(db: DBControllerV3) {
        val usedAddresses: MutableSet<String?>
        fun isUsed(address: String?): Boolean {
            return usedAddresses.contains(address)
        }

        fun addUsedAddress(address: String?) {
            usedAddresses.add(address)
        }

        init {
            var addresses: Set<String?>? = HashSet()
            try {
                addresses = db.allAddresses
                Log.d(TAG, "loaded " + addresses.size + " addresses from TX history: " + addresses)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to load addresses from TX history")
            }
            usedAddresses = Collections.synchronizedSet(addresses)
        }
    }

    companion object {
        const val TAG = "WalletUtil"
        @Throws(AddressFormatException::class)
        fun createMasterPubKeyFromXPub(xpubstr: String?): DeterministicKey {
            val xpubBytes = Base58.decodeChecked(xpubstr)
            val bb = ByteBuffer.wrap(xpubBytes)
            val prefix = bb.int
            if (prefix != 0x0488B21E) {
                throw AddressFormatException("invalid xpub version")
            }
            val chain = ByteArray(32)
            val pub = ByteArray(33)
            bb.get()
            bb.int
            bb.int
            bb[chain]
            bb[pub]
            return HDKeyDerivation.createMasterPubKeyFromBytes(pub, chain)
        }
    }

    init {
        xpubIndex = Settings.getXPubIndex(app, xPub);
        val key = createMasterPubKeyFromXPub(xPub)
        //This gets the receive chain from the xpub. If you want to generate change addresses, switch to 1 for the childNumber.
        accountKey = HDKeyDerivation.deriveChildKey(key, ChildNumber(0, false))
        addressBank = AddressBank(app.db)
    }
}