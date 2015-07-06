/*******************************************************************************
 * Software Name : RCS IMS Stack
 *
 * Copyright (C) 2010 France Telecom S.A.
 * Copyright (C) 2014 Sony Mobile Communications Inc.
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
 *
 * NOTE: This file has been modified by Sony Mobile Communications Inc.
 * Modifications are licensed under the License.
 ******************************************************************************/

package com.gsma.rcs.service.api;

import com.gsma.rcs.core.content.AudioContent;
import com.gsma.rcs.core.content.VideoContent;
import com.gsma.rcs.core.ims.protocol.sip.SipDialogPath;
import com.gsma.rcs.core.ims.protocol.sip.SipNetworkException;
import com.gsma.rcs.core.ims.protocol.sip.SipPayloadException;
import com.gsma.rcs.core.ims.service.ImsServiceError;
import com.gsma.rcs.core.ims.service.ImsServiceSession.InvitationStatus;
import com.gsma.rcs.core.ims.service.ImsServiceSession.TerminationReason;
import com.gsma.rcs.core.ims.service.ipcall.IPCallError;
import com.gsma.rcs.core.ims.service.ipcall.IPCallService;
import com.gsma.rcs.core.ims.service.ipcall.IPCallSession;
import com.gsma.rcs.core.ims.service.ipcall.IPCallStreamingSessionListener;
import com.gsma.rcs.provider.ipcall.IPCallPersistedStorageAccessor;
import com.gsma.rcs.provider.ipcall.IPCallStateAndReasonCode;
import com.gsma.rcs.service.broadcaster.IIPCallEventBroadcaster;
import com.gsma.rcs.service.ipcalldraft.AudioCodec;
import com.gsma.rcs.service.ipcalldraft.IIPCall;
import com.gsma.rcs.service.ipcalldraft.IIPCallPlayer;
import com.gsma.rcs.service.ipcalldraft.IIPCallRenderer;
import com.gsma.rcs.service.ipcalldraft.IPCall.ReasonCode;
import com.gsma.rcs.service.ipcalldraft.IPCall.State;
import com.gsma.rcs.service.ipcalldraft.VideoCodec;
import com.gsma.rcs.utils.logger.Logger;
import com.gsma.services.rcs.RcsService.Direction;
import com.gsma.services.rcs.contact.ContactId;

import android.os.RemoteException;

import javax2.sip.message.Response;

/**
 * IP call implementation
 * 
 * @author Jean-Marc AUFFRET
 */
public class IPCallImpl extends IIPCall.Stub implements IPCallStreamingSessionListener {

    private final String mCallId;

    private final IIPCallEventBroadcaster mBroadcaster;

    private final IPCallService mIPCallService;

    private final IPCallPersistedStorageAccessor mPersistentStorage;

    private final IPCallServiceImpl mIPCallServiceImpl;

    /**
     * Lock used for synchronisation
     */
    private final Object lock = new Object();

    /**
     * The logger
     */
    private static final Logger sLogger = Logger.getLogger(IPCallImpl.class.getName());

    /*
     * TODO: Fix reasoncode mapping in the switch.
     */
    private IPCallStateAndReasonCode toStateAndReasonCode(IPCallError error) {
        int ipCallError = error.getErrorCode();
        switch (ipCallError) {
            case IPCallError.SESSION_INITIATION_DECLINED:
            case IPCallError.SESSION_INITIATION_CANCELLED:
                return new IPCallStateAndReasonCode(State.REJECTED, ReasonCode.REJECTED_BY_REMOTE);
            case IPCallError.SESSION_INITIATION_FAILED:
                return new IPCallStateAndReasonCode(State.FAILED, ReasonCode.FAILED_INITIATION);
            case IPCallError.PLAYER_NOT_INITIALIZED:
            case IPCallError.PLAYER_FAILED:
            case IPCallError.RENDERER_NOT_INITIALIZED:
            case IPCallError.RENDERER_FAILED:
            case IPCallError.UNSUPPORTED_AUDIO_TYPE:
            case IPCallError.UNSUPPORTED_VIDEO_TYPE:
                return new IPCallStateAndReasonCode(State.FAILED, ReasonCode.FAILED_IPCALL);
            default:
                throw new IllegalArgumentException(new StringBuilder(
                        "Unknown reason in IPCallImpl.toStateAndReasonCode; ipCallError=")
                        .append(ipCallError).append("!").toString());
        }
    }

    private void setStateAndReasonCode(ContactId contact, State state, ReasonCode reasonCode) {
        if (mPersistentStorage.setStateAndReasonCode(state, reasonCode)) {
            mBroadcaster.broadcastIPCallStateChanged(contact, mCallId, state, reasonCode);
        }
    }

    /*
     * TODO: Fix reasoncode mapping in the switch.
     */
    private ReasonCode sessionAbortedReasonToReasonCode(TerminationReason reason) {
        switch (reason) {
            case TERMINATION_BY_SYSTEM:
            case TERMINATION_BY_TIMEOUT:
                return ReasonCode.ABORTED_BY_SYSTEM;
            case TERMINATION_BY_USER:
                return ReasonCode.ABORTED_BY_USER;
            default:
                throw new IllegalArgumentException(
                        "Unknown reason in IPCallImpl.sessionAbortedReasonToReasonCode; terminationReason="
                                + reason + "!");
        }
    }

    private void handleSessionRejected(ReasonCode reasonCode, ContactId contact) {
        if (sLogger.isActivated()) {
            sLogger.info("Call rejected; reasonCode=" + reasonCode + ".");
        }

        synchronized (lock) {
            mIPCallServiceImpl.removeIPCall(mCallId);
            setStateAndReasonCode(contact, State.REJECTED, reasonCode);
        }
    }

    /**
     * Constructor
     * 
     * @param callId Call ID
     * @param broadcaster IIPCallEventBroadcaster
     * @param ipCallService IPCallService
     * @param persistentStorage IPCallPersistedStorageAccessor
     * @param ipCallServiceImpl IPCallServiceImpl
     */
    public IPCallImpl(String callId, IIPCallEventBroadcaster broadcaster,
            IPCallService ipCallService, IPCallPersistedStorageAccessor persistentStorage,
            IPCallServiceImpl ipCallServiceImpl) {
        mCallId = callId;
        mBroadcaster = broadcaster;
        mIPCallService = ipCallService;
        mPersistentStorage = persistentStorage;
        mIPCallServiceImpl = ipCallServiceImpl;
    }

    /**
     * Returns the call ID of call
     * 
     * @return Call ID
     */
    public String getCallId() {
        return mCallId;
    }

    /**
     * Get remote contact identifier
     * 
     * @return ContactId
     */
    public ContactId getRemoteContact() {
        IPCallSession session = mIPCallService.getIPCallSession(mCallId);
        if (session == null) {
            return mPersistentStorage.getRemoteContact();
        }
        return session.getRemoteContact();
    }

    /**
     * Returns the state of the IP call
     * 
     * @return State
     */
    public int getState() {
        IPCallSession session = mIPCallService.getIPCallSession(mCallId);
        if (session == null) {
            return mPersistentStorage.getState().toInt();
        }
        SipDialogPath dialogPath = session.getDialogPath();
        if (dialogPath != null && dialogPath.isSessionEstablished()) {
            return State.STARTED.toInt();
        } else if (session.isInitiatedByRemote()) {
            if (session.isSessionAccepted()) {
                return State.ACCEPTING.toInt();
            }
            return State.INVITED.toInt();
        }
        return State.INITIATED.toInt();
    }

    /**
     * Returns the reason code of the state of the IP call
     * 
     * @return ReasonCode
     */
    public int getReasonCode() {
        IPCallSession session = mIPCallService.getIPCallSession(mCallId);
        if (session == null) {
            return mPersistentStorage.getReasonCode().toInt();
        }
        return ReasonCode.UNSPECIFIED.toInt();
    }

    /**
     * Returns the direction of the call (incoming or outgoing)
     * 
     * @return Direction
     * @see Direction
     */
    public int getDirection() {
        IPCallSession session = mIPCallService.getIPCallSession(mCallId);
        if (session == null) {
            return mPersistentStorage.getDirection().toInt();
        }
        if (session.isInitiatedByRemote()) {
            return Direction.INCOMING.toInt();
        }
        return Direction.OUTGOING.toInt();
    }

    /**
     * Returns the timestamp of the call (incoming or outgoing)
     * 
     * @return timestamp
     */
    public long getTimestamp() {

        IPCallSession session = mIPCallService.getIPCallSession(mCallId);
        if (session == null) {
            return mPersistentStorage.getTimestamp();
        }
        return session.getTimestamp();
    }

    /**
     * Accepts call invitation
     * 
     * @param player IP call player
     * @param renderer IP call renderer
     */
    public void acceptInvitation(IIPCallPlayer player, IIPCallRenderer renderer) {
        if (sLogger.isActivated()) {
            sLogger.info("Accept call invitation");
        }
        final IPCallSession session = mIPCallService.getIPCallSession(mCallId);
        if (session == null) {
            throw new ServerApiGenericException("No session with call ID:".concat(mCallId));
        }

        session.setPlayer(player);
        session.setRenderer(renderer);
        session.acceptSession();
    }

    /**
     * Rejects call invitation
     */
    public void rejectInvitation() {
        if (sLogger.isActivated()) {
            sLogger.info("Reject session invitation");
        }
        final IPCallSession session = mIPCallService.getIPCallSession(mCallId);
        if (session == null) {
            throw new ServerApiGenericException("No session with call ID:".concat(mCallId));
        }
        session.rejectSession(InvitationStatus.INVITATION_REJECTED_DECLINE);
    }

    /**
     * Aborts the call
     */
    public void abortCall() {
        if (sLogger.isActivated()) {
            sLogger.info("Abort session");
        }
        final IPCallSession session = mIPCallService.getIPCallSession(mCallId);
        if (session == null) {
            throw new ServerApiGenericException(new StringBuilder("Session with call ID '")
                    .append(mCallId).append("' not available!").toString());
        }
        mIPCallService.getImsModule().getCore().scheduleForBackgroundExecution(new Runnable() {
            @Override
            public void run() {
                session.terminateSession(TerminationReason.TERMINATION_BY_USER);
            }
        });
    }

    /**
     * Is video activated
     * 
     * @return Boolean
     */
    public boolean isVideo() {
        final IPCallSession session = mIPCallService.getIPCallSession(mCallId);
        if (session == null) {
            throw new ServerApiGenericException(new StringBuilder("Session with call ID '")
                    .append(mCallId).append("' not available!").toString());
        }
        return session.isVideoActivated();
    }

    /**
     * Add video stream
     */
    public void addVideo() {
        if (sLogger.isActivated()) {
            sLogger.info("Add video");
        }
        final IPCallSession session = mIPCallService.getIPCallSession(mCallId);
        if (session == null) {
            throw new ServerApiGenericException(new StringBuilder(
                    "Unable to add video since session with call ID '").append(mCallId)
                    .append("' not available!").toString());
        }
        mIPCallService.getImsModule().getCore().scheduleForBackgroundExecution(new Runnable() {
            @Override
            public void run() {
                try {
                    session.addVideo();
                } catch (SipPayloadException e) {
                    sLogger.error("Unable to add video for call ID : ".concat(mCallId), e);
                    session.handleError(new ImsServiceError(IPCallError.SESSION_INITIATION_FAILED,
                            e));
                } catch (SipNetworkException e) {
                    if (sLogger.isActivated()) {
                        sLogger.debug(e.getMessage());
                    }
                    session.handleError(new ImsServiceError(IPCallError.SESSION_INITIATION_FAILED,
                            e));
                } catch (RuntimeException e) {
                    /*
                     * Normally we are not allowed to catch runtime exceptions as these are genuine
                     * bugs which should be handled/fixed within the code. However the cases when we
                     * are executing operations on a thread unhandling such exceptions will
                     * eventually lead to exit the system and thus can bring the whole system down,
                     * which is not intended.
                     */
                    sLogger.error("Unable to add video for call ID : ".concat(mCallId), e);
                    session.handleError(new ImsServiceError(IPCallError.SESSION_INITIATION_FAILED,
                            e));
                }
            }
        });
    }

    /**
     * Remove video stream
     */
    public void removeVideo() {
        if (sLogger.isActivated()) {
            sLogger.info("Remove video");
        }
        final IPCallSession session = mIPCallService.getIPCallSession(mCallId);
        if (session == null) {
            throw new ServerApiGenericException(new StringBuilder(
                    "Unable to remove video since session with call ID '").append(mCallId)
                    .append("' not available!").toString());
        }
        mIPCallService.getImsModule().getCore().scheduleForBackgroundExecution(new Runnable() {
            @Override
            public void run() {
                try {
                    session.removeVideo();
                } catch (SipPayloadException e) {
                    sLogger.error("Unable to remove video for call ID : ".concat(mCallId), e);
                    session.handleError(new ImsServiceError(IPCallError.SESSION_INITIATION_FAILED,
                            e));
                } catch (SipNetworkException e) {
                    if (sLogger.isActivated()) {
                        sLogger.debug(e.getMessage());
                    }
                    session.handleError(new ImsServiceError(IPCallError.SESSION_INITIATION_FAILED,
                            e));
                } catch (RuntimeException e) {
                    /*
                     * Normally we are not allowed to catch runtime exceptions as these are genuine
                     * bugs which should be handled/fixed within the code. However the cases when we
                     * are executing operations on a thread unhandling such exceptions will
                     * eventually lead to exit the system and thus can bring the whole system down,
                     * which is not intended.
                     */
                    sLogger.error("Unable to remove video for call ID : ".concat(mCallId), e);
                    session.handleError(new ImsServiceError(IPCallError.SESSION_INITIATION_FAILED,
                            e));
                }
            }
        });
    }

    /**
     * Accept invitation to add video
     */
    // TODO
    public void acceptAddVideo() {
        if (sLogger.isActivated()) {
            sLogger.info("Accept invitation to add video");
        }
        final IPCallSession session = mIPCallService.getIPCallSession(mCallId);
        if (session == null) {
            throw new ServerApiGenericException(new StringBuilder(
                    "Unable to accept add video since session with call ID '").append(mCallId)
                    .append("' not available!").toString());
        }
        mIPCallService.getImsModule().getCore().scheduleForBackgroundExecution(new Runnable() {
            @Override
            public void run() {
                session.getUpdateSessionManager().acceptReInvite();
            }
        });
    }

    /**
     * Reject invitation to add video
     */
    // TODO
    public void rejectAddVideo() {
        if (sLogger.isActivated()) {
            sLogger.info("Reject invitation to add video");
        }
        final IPCallSession session = mIPCallService.getIPCallSession(mCallId);
        if (session == null) {
            throw new ServerApiGenericException(new StringBuilder(
                    "Unable to reject add video since session with call ID '").append(mCallId)
                    .append("' not available!").toString());
        }
        session.setVideoContent(null);
        mIPCallService.getImsModule().getCore().scheduleForBackgroundExecution(new Runnable() {
            @Override
            public void run() {
                session.getUpdateSessionManager().rejectReInvite(603);
            }
        });
    }

    /**
     * Puts the call on hold
     */
    public void holdCall() {
        if (sLogger.isActivated()) {
            sLogger.info("Hold call");
        }
        final IPCallSession session = mIPCallService.getIPCallSession(mCallId);
        if (session == null) {
            throw new ServerApiGenericException(new StringBuilder(
                    "Unable to hold call since session with call ID '").append(mCallId)
                    .append("' not available!").toString());
        }
        mIPCallService.getImsModule().getCore().scheduleForBackgroundExecution(new Runnable() {
            @Override
            public void run() {
                try {
                    session.setOnHold(true);
                } catch (SipPayloadException e) {
                    sLogger.error("Unable to hold call for call ID : ".concat(mCallId), e);
                    session.handleError(new ImsServiceError(IPCallError.SESSION_INITIATION_FAILED,
                            e));
                } catch (SipNetworkException e) {
                    if (sLogger.isActivated()) {
                        sLogger.debug(e.getMessage());
                    }
                    session.handleError(new ImsServiceError(IPCallError.SESSION_INITIATION_FAILED,
                            e));
                } catch (RuntimeException e) {
                    /*
                     * Normally we are not allowed to catch runtime exceptions as these are genuine
                     * bugs which should be handled/fixed within the code. However the cases when we
                     * are executing operations on a thread unhandling such exceptions will
                     * eventually lead to exit the system and thus can bring the whole system down,
                     * which is not intended.
                     */
                    sLogger.error("Unable to hold call for call ID : ".concat(mCallId), e);
                    session.handleError(new ImsServiceError(IPCallError.SESSION_INITIATION_FAILED,
                            e));
                }
            }
        });
    }

    /**
     * Continues the call that hold's on
     */
    public void continueCall() {
        if (sLogger.isActivated()) {
            sLogger.info("Continue call");
        }
        final IPCallSession session = mIPCallService.getIPCallSession(mCallId);
        if (session == null) {
            throw new ServerApiGenericException(new StringBuilder(
                    "Unable to continue call since session with call ID '").append(mCallId)
                    .append("' not available!").toString());
        }
        mIPCallService.getImsModule().getCore().scheduleForBackgroundExecution(new Runnable() {
            @Override
            public void run() {
                try {
                    session.setOnHold(false);
                } catch (SipPayloadException e) {
                    sLogger.error("Unable to continue call for call ID : ".concat(mCallId), e);
                    session.handleError(new ImsServiceError(IPCallError.SESSION_INITIATION_FAILED,
                            e));
                } catch (SipNetworkException e) {
                    if (sLogger.isActivated()) {
                        sLogger.debug(e.getMessage());
                    }
                    session.handleError(new ImsServiceError(IPCallError.SESSION_INITIATION_FAILED,
                            e));
                } catch (RuntimeException e) {
                    /*
                     * Normally we are not allowed to catch runtime exceptions as these are genuine
                     * bugs which should be handled/fixed within the code. However the cases when we
                     * are executing operations on a thread unhandling such exceptions will
                     * eventually lead to exit the system and thus can bring the whole system down,
                     * which is not intended.
                     */
                    sLogger.error("Unable to continue call for call ID : ".concat(mCallId), e);
                    session.handleError(new ImsServiceError(IPCallError.SESSION_INITIATION_FAILED,
                            e));
                }
            }
        });
    }

    /**
     * Is call on hold
     * 
     * @return Boolean
     */
    public boolean isOnHold() {
        // TODO
        return false;
    }

    /**
     * Returns the video codec used during sharing
     * 
     * @return VideoCodec
     */
    public VideoCodec getVideoCodec() {
        final IPCallSession session = mIPCallService.getIPCallSession(mCallId);
        if (session == null) {
            throw new ServerApiGenericException(new StringBuilder(
                    "Unable to get VideoCodec since session with call ID '").append(mCallId)
                    .append("' not available!").toString());
        }
        try {
            return session.getPlayer().getVideoCodec();
        } catch (RemoteException e) {
            if (sLogger.isActivated()) {
                sLogger.info("Unable to retrieve the video codec!");
            }
            return null;
        }
    }

    /**
     * Returns the audio codec used during sharing
     * 
     * @return AudioCodec
     */
    public AudioCodec getAudioCodec() {
        final IPCallSession session = mIPCallService.getIPCallSession(mCallId);
        if (session == null) {
            throw new ServerApiGenericException(new StringBuilder(
                    "Unable to get AudioCodec since session with call ID '").append(mCallId)
                    .append("' not available!").toString());
        }
        try {
            return session.getPlayer().getAudioCodec();
        } catch (RemoteException e) {
            if (sLogger.isActivated()) {
                sLogger.info("Unable to retrieve the audio codec!");
            }
            return null;
        }
    }

    /*------------------------------- SESSION EVENTS ----------------------------------*/

    /**
     * Session is started
     */
    public void handleSessionStarted(ContactId contact) {
        if (sLogger.isActivated()) {
            sLogger.info("Call started");
        }
        synchronized (lock) {
            mBroadcaster.broadcastIPCallStateChanged(contact, mCallId, State.STARTED,
                    ReasonCode.UNSPECIFIED);
        }
    }

    /**
     * Session has been aborted
     * 
     * @param reason Termination reason
     */
    public void handleSessionAborted(ContactId contact, TerminationReason reason) {
        if (sLogger.isActivated()) {
            sLogger.info(new StringBuilder("Call aborted (reason ").append(reason).append(")")
                    .toString());
        }
        synchronized (lock) {
            ReasonCode reasonCode = sessionAbortedReasonToReasonCode(reason);
            setStateAndReasonCode(contact, State.ABORTED, reasonCode);
            mIPCallServiceImpl.removeIPCall(mCallId);
        }
    }

    /**
     * Session has been terminated by remote
     */
    public void handleSessionTerminatedByRemote(ContactId contact) {
        if (sLogger.isActivated()) {
            sLogger.info("Call terminated by remote");
        }
        synchronized (lock) {
            mIPCallServiceImpl.removeIPCall(mCallId);
            setStateAndReasonCode(contact, State.ABORTED, ReasonCode.ABORTED_BY_REMOTE);
        }
    }

    /**
     * IP Call error
     * 
     * @param contact Remote contact
     * @param error Error
     */
    public void handleCallError(ContactId contact, IPCallError error) {
        if (sLogger.isActivated()) {
            sLogger.info("Session error " + error.getErrorCode());
        }
        IPCallStateAndReasonCode stateAndReasonCode = toStateAndReasonCode(error);
        State state = stateAndReasonCode.getState();
        ReasonCode reasonCode = stateAndReasonCode.getReasonCode();
        synchronized (lock) {
            mIPCallServiceImpl.removeIPCall(mCallId);
            setStateAndReasonCode(contact, state, reasonCode);
        }
    }

    /**
     * Add video invitation
     * 
     * @param videoEncoding Video encoding
     * @param width Video width
     * @param height Video height
     */
    public void handleAddVideoInvitation(ContactId contact, String videoEncoding, int videoWidth,
            int videoHeight) {
        if (sLogger.isActivated()) {
            sLogger.info("Add video invitation");
        }
        synchronized (lock) {
            // TODO : Verify if the state change callback listener used is the right one!
            mBroadcaster.broadcastIPCallStateChanged(contact, mCallId, State.INVITED,
                    ReasonCode.UNSPECIFIED);
        }
    }

    /**
     * Remove video invitation
     */
    public void handleRemoveVideo(ContactId contact) {
        if (sLogger.isActivated()) {
            sLogger.info("Remove video invitation");
        }
        synchronized (lock) {
            // TODO : Verify if the state change callback listener used is the right one!
            mBroadcaster.broadcastIPCallStateChanged(contact, mCallId, State.ABORTED,
                    ReasonCode.ABORTED_BY_REMOTE);
        }
    }

    /**
     * Add video has been accepted by user
     */
    public void handleAddVideoAccepted(ContactId contact) {
        if (sLogger.isActivated()) {
            sLogger.info("Add video accepted");
        }
        synchronized (lock) {
            // TODO : Verify if the state change callback listener used is the
            // right one!
            mBroadcaster.broadcastIPCallStateChanged(contact, mCallId, State.STARTED,
                    ReasonCode.UNSPECIFIED);
        }
    }

    /**
     * Remove video has been accepted by user
     */
    public void handleRemoveVideoAccepted(ContactId contact) {
        if (sLogger.isActivated()) {
            sLogger.info("Remove video accepted");
        }
        synchronized (lock) {
            // TODO : Verify if the state change callback listener used is the
            // right one!
            mBroadcaster.broadcastIPCallStateChanged(contact, mCallId, State.ABORTED,
                    ReasonCode.ABORTED_BY_USER);
        }
    }

    /**
     * Add video has been aborted
     * 
     * @param reason Termination reason
     */
    public void handleAddVideoAborted(ContactId contact, TerminationReason reason) {
        if (sLogger.isActivated()) {
            sLogger.info(new StringBuilder("Add video aborted (reason ").append(reason).append(")")
                    .toString());
        }
        ReasonCode reasonCode = sessionAbortedReasonToReasonCode(reason);
        synchronized (lock) {
            setStateAndReasonCode(contact, State.ABORTED, reasonCode);
        }
    }

    /**
     * Remove video has been aborted
     * 
     * @param reason Termination reason
     */
    public void handleRemoveVideoAborted(ContactId contact, TerminationReason reason) {
        if (sLogger.isActivated()) {
            sLogger.info(new StringBuilder("Remove video aborted (reason ").append(reason)
                    .append(")").toString());
        }
        ReasonCode reasonCode = sessionAbortedReasonToReasonCode(reason);
        synchronized (lock) {
            setStateAndReasonCode(contact, State.ABORTED, reasonCode);
        }
    }

    /**
     * Call Hold invitation
     */
    public void handleCallHold(ContactId contact) {
        if (sLogger.isActivated()) {
            sLogger.info("Call hold");
        }
        synchronized (lock) {
            setStateAndReasonCode(contact, State.HOLD, ReasonCode.UNSPECIFIED);
        }
    }

    /**
     * Call Resume invitation
     */
    public void handleCallResume(ContactId contact) {
        if (sLogger.isActivated()) {
            sLogger.info("Call Resume invitation");
        }
        synchronized (lock) {
            setStateAndReasonCode(contact, State.STARTED, ReasonCode.UNSPECIFIED);
        }
    }

    /**
     * Call Hold has been accepted
     */
    public void handleCallHoldAccepted(ContactId contact) {
        if (sLogger.isActivated()) {
            sLogger.info("Call Hold accepted");
        }
        synchronized (lock) {
            // TODO : Verify if the state change callback listener used is the right one!
            mBroadcaster.broadcastIPCallStateChanged(contact, mCallId, State.HOLD,
                    ReasonCode.UNSPECIFIED);
        }
    }

    /**
     * Call Hold has been aborted
     * 
     * @param reason Termination reason
     */
    public void handleCallHoldAborted(ContactId contact, TerminationReason reason) {
        if (sLogger.isActivated()) {
            sLogger.info(new StringBuilder("Call Hold aborted (reason ").append(reason).append(")")
                    .toString());
        }
        ReasonCode reasonCode = sessionAbortedReasonToReasonCode(reason);
        synchronized (lock) {
            mBroadcaster.broadcastIPCallStateChanged(contact, mCallId, State.ABORTED, reasonCode);
        }
    }

    /**
     * Call Resume has been accepted
     */
    public void handleCallResumeAccepted(ContactId contact) {
        if (sLogger.isActivated()) {
            sLogger.info("Call Resume accepted");
        }
        synchronized (lock) {
            // TODO : Verify if the state change callback listener used is the right one!
            mBroadcaster.broadcastIPCallStateChanged(contact, mCallId, State.STARTED,
                    ReasonCode.UNSPECIFIED);
        }
    }

    /**
     * Call Resume has been aborted
     * 
     * @param reason Termination reason
     */
    public void handleCallResumeAborted(ContactId contact) {
        if (sLogger.isActivated()) {
            sLogger.info("Call Resume aborted");
        }
        synchronized (lock) {
            // TODO : Verify if the state change callback listener used is the right one!
            mBroadcaster.broadcastIPCallStateChanged(contact, mCallId, State.ABORTED,
                    ReasonCode.ABORTED_BY_SYSTEM);
        }
    }

    /**
     * Called user is Busy
     */
    public void handle486Busy(ContactId contact) {
        // Notify event listeners
        // TODO : Verify if the state change callback listener used is the right one!
        mBroadcaster.broadcastIPCallStateChanged(contact, mCallId, State.REJECTED,
                ReasonCode.REJECTED_TIME_OUT);
    }

    /**
     * Video stream has been resized
     * 
     * @param width Video width
     * @param height Video height
     */
    public void handleVideoResized(int width, int height) {
        synchronized (lock) {
            if (sLogger.isActivated()) {
                sLogger.info("Video resized to " + width + "x" + height);
            }

            // Notify event listeners
            /*
             * final int N = listeners.beginBroadcast(); for (int i = 0; i < N; i++) { try {
             * listeners.getBroadcastItem(i).handleVideoResized(width, height); } catch (Exception
             * e) { if (logger.isActivated()) { logger.error("Can't notify listener", e); } } }
             * listeners.finishBroadcast();
             */
            // TODO
        }
    }

    @Override
    public void handleSessionAccepted(ContactId contact) {
        if (sLogger.isActivated()) {
            sLogger.info("Accepting call");
        }

        synchronized (lock) {
            setStateAndReasonCode(contact, State.ACCEPTING, ReasonCode.UNSPECIFIED);
        }
    }

    @Override
    public void handleSessionRejected(ContactId contact, TerminationReason reason) {
        switch (reason) {
            case TERMINATION_BY_USER:
                handleSessionRejected(ReasonCode.REJECTED_BY_USER, contact);
                break;
            case TERMINATION_BY_SYSTEM:
                /* Intentional fall through */
            case TERMINATION_BY_CONNECTION_LOST:
                handleSessionRejected(ReasonCode.REJECTED_BY_SYSTEM, contact);
                break;
            case TERMINATION_BY_TIMEOUT:
                handleSessionRejected(ReasonCode.REJECTED_TIME_OUT, contact);
                break;
            case TERMINATION_BY_REMOTE:
                handleSessionRejected(ReasonCode.REJECTED_BY_REMOTE, contact);
                break;
            default:
                throw new IllegalArgumentException(new StringBuilder(
                        "Unknown reason RejectedReason=").append(reason).append("!").toString());
        }
    }

    @Override
    public void handleSessionInvited(ContactId contact, AudioContent audio, VideoContent video,
            long timestamp) {
        if (sLogger.isActivated()) {
            sLogger.info("Invited to ipcall session");
        }
        synchronized (lock) {
            mPersistentStorage.addCall(contact, Direction.INCOMING, audio, video, State.INVITED,
                    ReasonCode.UNSPECIFIED, timestamp);
        }

        mBroadcaster.broadcastIPCallInvitation(mCallId);
    }
}
