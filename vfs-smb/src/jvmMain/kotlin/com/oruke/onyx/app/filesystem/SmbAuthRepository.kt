package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.net.MalformedURLException
import java.net.UnknownHostException
import java.util.Properties


interface SmbAuthRepository {
    fun authContext(location: String): VfsAuthContext

    data object None : SmbAuthRepository {
        override fun authContext(location: String): VfsAuthContext = VfsAuthContext.None
    }
}

class RemoteAuthStoreSmbAuthRepository(
    private val remoteAuthStore: RemoteAuthStore,
) : SmbAuthRepository {
    override fun authContext(location: String): VfsAuthContext {
        return remoteAuthStore.authContext(VfsProtocol.SMB, location)
    }
}
