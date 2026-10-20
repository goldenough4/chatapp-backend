# Implementation Plan - Final Comprehensive Fix for ANR, Icons, and Incoming Call Screen

This plan addresses all remaining critical issues permanently:
1. **ANR / App Freeze on Second Call**:
   - `PeerConnectionFactory.initialize()` and `PeerConnectionFactory` disposal can block or throw if initialized multiple times or closed incorrectly. We will safely guard factory initialization and ensure complete disposal and listener removal in `endCall()`.
2. **Missing UI Icons (Blank Gray Circles)**:
   - Android standard platform drawables like `@android:drawable/ic_lock_silent_mode_off` or `@android:drawable/ic_menu_camera` often render blank or fail across different OEM themes. We will create dedicated high-visibility vector drawables (`ic_call_speaker.xml`, `ic_call_video.xml`, `ic_call_mic.xml`, `ic_call_more.xml`, `ic_call_share.xml`, `ic_call_end.xml`, `ic_call_accept.xml`, `ic_call_decline.xml`) and reference them directly in `activity_call.xml`.
3. **Missing Incoming Call Accept/Decline Screen**:
   - When `is_incoming == true`, `CallActivity` will show a dedicated WhatsApp-style incoming call ringing layout overlay with caller photo, name, ringtone, vibration, and distinct **Green Accept** and **Red Decline** buttons. Only when the user taps **Accept** does it initialize WebRTC and join the call.

## User Review Required

> [!IMPORTANT]
> - **Vector Drawables**: We will generate crisp XML vector assets for all call controls and Accept/Decline actions.
> - **Incoming Call Interception**: `CallActivity` will stay in ringing mode with Accept/Decline buttons when `is_incoming = true` until explicit user action.

## Proposed Changes

### Vector Drawables for Call Controls
#### [NEW] [ic_call_speaker.xml](file:///home/goldenough8/AndroidStudioProjects/ChatApp/app/src/main/res/drawable/ic_call_speaker.xml)
#### [NEW] [ic_call_video.xml](file:///home/goldenough8/AndroidStudioProjects/ChatApp/app/src/main/res/drawable/ic_call_video.xml)
#### [NEW] [ic_call_mic.xml](file:///home/goldenough8/AndroidStudioProjects/ChatApp/app/src/main/res/drawable/ic_call_mic.xml)
#### [NEW] [ic_call_more.xml](file:///home/goldenough8/AndroidStudioProjects/ChatApp/app/src/main/res/drawable/ic_call_more.xml)
#### [NEW] [ic_call_share.xml](file:///home/goldenough8/AndroidStudioProjects/ChatApp/app/src/main/res/drawable/ic_call_share.xml)
#### [NEW] [ic_call_end.xml](file:///home/goldenough8/AndroidStudioProjects/ChatApp/app/src/main/res/drawable/ic_call_end.xml)
#### [NEW] [ic_call_accept.xml](file:///home/goldenough8/AndroidStudioProjects/ChatApp/app/src/main/res/drawable/ic_call_accept.xml)
#### [NEW] [ic_call_decline.xml](file:///home/goldenough8/AndroidStudioProjects/ChatApp/app/src/main/res/drawable/ic_call_decline.xml)

### Call Activity & Layout
#### [MODIFY] [activity_call.xml](file:///home/goldenough8/AndroidStudioProjects/ChatApp/app/src/main/res/layout/activity_call.xml)
- Add dedicated incoming call action container with Green Accept and Red Decline buttons.
- Update all 2x3 grid buttons to use the new custom vector drawables.

#### [MODIFY] [CallActivity.kt](file:///home/goldenough8/AndroidStudioProjects/ChatApp/app/src/main/java/com/gold/chatapp/CallActivity.kt)
- Implement rigorous ANR-free WebRTC teardown (`endCall`).
- Add incoming ringing mode when `is_incoming = true`, playing ringtone/vibration and waiting for user to tap Accept or Decline before initializing WebRTC.

## Verification Plan

### Automated Tests
- Run `app:assembleDebug` to verify clean compilation.
