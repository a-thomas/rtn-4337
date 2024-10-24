package io.cometh.rtn4337

import android.content.Context
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import io.cometh.android4337.UserOperation
import io.cometh.android4337.bundler.SimpleBundlerClient
import io.cometh.android4337.bundler.response.UserOperationByHash
import io.cometh.android4337.bundler.response.UserOperationReceipt
import io.cometh.android4337.paymaster.PaymasterClient
import io.cometh.android4337.safe.SafeAccount
import io.cometh.android4337.safe.SafeConfig
import io.cometh.android4337.safe.signer.Signer
import io.cometh.android4337.safe.signer.eoa.EOASigner
import io.cometh.android4337.safe.signer.passkey.PasskeySigner
import io.cometh.android4337.toMap
import io.cometh.android4337.utils.hexToAddress
import io.cometh.android4337.utils.hexToBigInt
import io.cometh.android4337.utils.toHex
import io.cometh.rtn4337.types.CommonParams
import io.cometh.rtn4337.types.UserOperationRecord
import io.cometh.rtn4337.types.toUserOp
import io.cometh.rtn4337.types.verifyMandatoryUrls
import org.web3j.crypto.Credentials
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Async

class rtn4337Module : Module() {
    override fun definition() = ModuleDefinition {
        Name("rtn4337")

        // SIGNER

        AsyncFunction("createPasskeySigner") Coroutine { rpId: String, userName: String ->
            val passkeySigner = PasskeySigner.withSharedSigner(appContext.reactContext!!, rpId, userName)
            return@Coroutine mapOf(
                "x" to passkeySigner.passkey.x.toHex(),
                "y" to passkeySigner.passkey.y.toHex(),
            )
        }

        // SAFE ACCOUNT

        AsyncFunction("sendUserOperation") Coroutine { params: CommonParams, to: String, value: String, data: String, delegateCall: Boolean ->
            return@Coroutine getSafeAccount(appContext.reactContext!!, params).sendUserOperation(
                to.hexToAddress(),
                value = value.hexToBigInt(),
                data = data.toByteArray(),
                delegateCall = delegateCall
            )
        }

        AsyncFunction("predictAddress") Coroutine { chainId: Int, rpcUrl: String, signer: Map<String, Any>, safeConfig: Map<String, String> ->
            return@Coroutine SafeAccount.predictAddress(getSigner(appContext.reactContext!!, signer), HttpService(rpcUrl), getSafeConfig(safeConfig))
        }

        AsyncFunction("getOwners") Coroutine { params: CommonParams ->
            return@Coroutine getSafeAccount(appContext.reactContext!!, params).getOwners()?.map { it.value }
        }

        AsyncFunction("isDeployed") Coroutine { params: CommonParams ->
            return@Coroutine getSafeAccount(appContext.reactContext!!, params).isDeployed()
        }

        AsyncFunction("addOwner") Coroutine { params: CommonParams, owner: String ->
            return@Coroutine getSafeAccount(appContext.reactContext!!, params).addOwner(owner.hexToAddress())
        }

        AsyncFunction("prepareUserOperation") Coroutine { params: CommonParams, to: String, value: String, data: String, delegateCall: Boolean ->
            return@Coroutine getSafeAccount(appContext.reactContext!!, params)
                .prepareUserOperation(
                    to.hexToAddress(),
                    value = value.hexToBigInt(),
                    data = data.toByteArray(),
                    delegateCall = delegateCall
                ).toMap()
        }

        AsyncFunction("signUserOperation") Coroutine { params: CommonParams, userOp: UserOperationRecord ->
            return@Coroutine getSafeAccount(appContext.reactContext!!, params).signUserOperation(userOp.toUserOp()).toHex()
        }

        // BUNDLER

        AsyncFunction("ethGetUserOperationReceipt") Coroutine { bundlerUrl: String, userOpHash: String ->
            val resp = SimpleBundlerClient(HttpService(bundlerUrl)).ethGetUserOperationReceipt(userOpHash).send()
            val receipt = resp.result ?: return@Coroutine null
            return@Coroutine receipt.toMap()
        }

        AsyncFunction("ethGetUserOperationByHash") Coroutine { bundlerUlr: String, userOpHash: String ->
            val resp = SimpleBundlerClient(HttpService(bundlerUlr)).ethGetUserOperationByHash(userOpHash).send()
            val userOp = resp.result ?: return@Coroutine null
            return@Coroutine userOp.toMap()
        }

    }
}

private suspend fun getSafeAccount(context: Context, params: CommonParams): SafeAccount {
    params.verifyMandatoryUrls()
    requireNotNull(params.chainId) { "chainId is required" }
    requireNotNull(params.signer) { "signer is required" }
    val rpcService = HttpService(params.rpcUrl)
    val bundlerClient = SimpleBundlerClient(HttpService(params.bundlerUrl))
    val paymasterClient = if (params.paymasterUrl != null) PaymasterClient(params.paymasterUrl) else null
    val signer = getSigner(context, params.signer)
    val safeConfig = getSafeConfig(params.config!!)
    return if (params.address != null) {
        SafeAccount.fromAddress(params.address, signer, bundlerClient, params.chainId, rpcService, paymasterClient = paymasterClient, config = safeConfig)
    } else {
        SafeAccount.createNewAccount(signer, bundlerClient, params.chainId, rpcService, paymasterClient = paymasterClient, config = safeConfig)
    }
}

private fun getSafeConfig(config: Map<String, String>): SafeConfig {
    return SafeConfig(
        safeModuleSetupAddress = config["safeModuleSetupAddress"]!!,
        safe4337ModuleAddress = config["safe4337ModuleAddress"]!!,
        safeSingletonL2Address = config["safeSingletonL2Address"]!!,
        safeProxyFactoryAddress = config["safeProxyFactoryAddress"]!!,
        safeWebAuthnSharedSignerAddress = config["safeWebAuthnSharedSignerAddress"]!!,
        safeMultiSendAddress = config["safeMultiSendAddress"]!!,
        safeP256VerifierAddress = config["safeP256VerifierAddress"]!!,
        safeWebauthnSignerFactoryAddress = config["safeWebauthnSignerFactoryAddress"]!!,
    )
}

private suspend fun getSigner(context: Context, signer: Map<String, Any>): Signer {
    if (signer.containsKey("rpId")) {
        val rpId = signer["rpId"] as String
        val userName = signer["userName"] as String
        return PasskeySigner.withSharedSigner(context, rpId, userName)
    } else {
        val privateKey = signer["privateKey"] as String
        return EOASigner(Credentials.create(privateKey))
    }
}

// map useropeartionreceip to map
private fun UserOperationReceipt.toMap(): Map<String, Any?> {
    return mapOf("userOpHash" to userOpHash,
        "sender" to sender,
        "nonce" to nonce,
        "actualGasUsed" to actualGasUsed,
        "actualGasCost" to actualGasCost,
        "success" to success,
        "paymaster" to paymaster,
        "receipt" to receipt.toMap(),
        "logs" to logs.map { it.toMap() })
}

private fun TransactionReceipt.toMap(): Map<String, Any?> {
    return mapOf(
        "transactionHash" to transactionHash,
        "transactionIndex" to transactionIndexRaw,
        "blockHash" to blockHash,
        "blockNumber" to blockNumberRaw,
        "cumulativeGasUsed" to cumulativeGasUsedRaw,
        "gasUsed" to gasUsedRaw,
        "contractAddress" to contractAddress,
        "root" to root,
        "status" to status,
        "from" to from,
        "to" to to,
        "logs" to logs.map { it.toMap() },
        "logsBloom" to logsBloom,
        "revertReason" to revertReason,
        "type" to type,
        "effectiveGasPrice" to effectiveGasPrice
    )
}

private fun org.web3j.protocol.core.methods.response.Log.toMap(): Map<String, Any?> {
    return mapOf(
        "logIndex" to logIndexRaw,
        "transactionIndex" to transactionIndexRaw,
        "transactionHash" to transactionHash,
        "blockHash" to blockHash,
        "blockNumber" to blockNumberRaw,
        "address" to address,
        "data" to data,
        "topics" to topics
    )
}

private fun UserOperationByHash.toMap(): Map<String, Any?> {
    return mapOf(
        "userOperation" to userOperation.toMap(),
        "entryPoint" to entryPoint,
        "transactionHash" to transactionHash,
        "blockNumber" to blockNumber,
        "blockHash" to blockHash,
    )
}
