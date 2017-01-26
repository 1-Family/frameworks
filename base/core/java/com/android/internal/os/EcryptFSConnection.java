/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.os;

import android.net.Credentials;
import android.net.LocalSocket;
import android.os.Process;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;

/**
 * A connection that can make spawn requests.
 */
class EcryptFSConnection {
    private static final String TAG = "EcryptFSConnection";


    /**
     * {@link android.net.LocalSocket#setSoTimeout} value for connections.
     * Effectively, the amount of time a requestor has between the start of
     * the request and the completed request. The select-loop mode Zygote
     * doesn't have the logic to return to the select loop in the middle of
     * a request, so we need to time out here to avoid being denial-of-serviced.
     */
    private static final int CONNECTION_TIMEOUT_MILLIS = 1000;


    /**
     * The command socket.
     *
     * mSocket is retained in the child process in "peer wait" mode, so
     * that it closes when the child process terminates. In other cases,
     * it is closed in the peer.
     */
    private final LocalSocket mSocket;
    private final DataOutputStream mSocketOutStream;
    private final DataInputStream mSocketInStream;
    private final Credentials peer;

    /**
     * Constructs instance from connected socket.
     *
     * @param socket non-null; connected socket
     * @throws IOException
     */
    EcryptFSConnection(LocalSocket socket) throws IOException {
        mSocket = socket;

        mSocketOutStream = new DataOutputStream(socket.getOutputStream());

        mSocketInStream = new DataInputStream(socket.getInputStream());

        mSocket.setSoTimeout(CONNECTION_TIMEOUT_MILLIS*10);
                
        try {
            peer = mSocket.getPeerCredentials();
        } catch (IOException ex) {
            Log.e(TAG, "Cannot read peer credentials", ex);
            throw ex;
        }
    }

    /**
     * Closes socket associated with this connection.
     */
    void closeSocket() {
        try {
            mSocket.close();
        } catch (IOException ex) {
            Log.e(TAG, "Exception while closing command socket in parent", ex);
        }
    }
    
    public boolean doWork(PrivateKey privateKey,PublicKey publicKey) {
    	int bytesRead = 0;
    	byte[] initialBuffer = new byte[1000];
    	try {
	    	bytesRead = mSocketInStream.read(initialBuffer);
	    	if (bytesRead >0) {
	    		initialBuffer[bytesRead] = 0;
				byte[] buffer = new byte[bytesRead-1];
				System.arraycopy(initialBuffer, 1, buffer, 0, bytesRead-1);				        		
	        	byte[] to_key = null;
	    		if (initialBuffer[0] == 0) {
	    			to_key = rsaEncrypt(buffer, publicKey);
		            mSocketOutStream.write(to_key);
		            mSocketOutStream.flush();
	    		} else if (initialBuffer[0] == 1) {
	    			to_key = rsaDecrypt(buffer, privateKey);
		            mSocketOutStream.write(to_key);
		            mSocketOutStream.flush();
	    		}
	    	} else {
	    		Log.e(TAG,"Failed to read from socket");
	    	}
    	} catch (Exception e) {
    		Log.e(TAG, "Failed to handle data from socket", e);
    	}
		return true;
    }
    
    public boolean hasPermissions() {
    	boolean rv = false;
    	if (peer.getUid() == Process.getUidForName("ecryptfs")) {
    		int[] pids = Process.getPidsForCommands(new String[]{"/system/bin/ecryptfsd"});
    		if (pids.length == 1 && pids[0] == peer.getPid()) {
    			rv = true;
    		} else {
    			Log.e(TAG, "The peer is not an authorized process");
    		}
    	} else {
    		Log.e(TAG, "The peer has no autorized uid");
    	}
    	return rv;
    }
    
    private byte[] rsaEncrypt (byte[] plain, PublicKey publicKey) {
        byte[] encryptedBytes = null;
        Cipher cipher;
		try {
			cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding", "AndroidOpenSSL");
			cipher.init(Cipher.ENCRYPT_MODE, publicKey);
			encryptedBytes = cipher.doFinal(plain);
		} catch (Exception e) {
			e.printStackTrace();
		}
        
        return encryptedBytes;
    }
    
    public byte[] rsaDecrypt(byte[] encrypted, PrivateKey privateKey) throws NoSuchAlgorithmException, NoSuchPaddingException,
    InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
    	Cipher cipher;
		try {
			cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding", "AndroidOpenSSL");
			cipher.init(Cipher.DECRYPT_MODE, privateKey);
			byte[] decryptedBytes = cipher.doFinal(encrypted);
			return decryptedBytes;
		} catch (NoSuchProviderException e) {
			e.printStackTrace();
		}
		return null;
    }
}
