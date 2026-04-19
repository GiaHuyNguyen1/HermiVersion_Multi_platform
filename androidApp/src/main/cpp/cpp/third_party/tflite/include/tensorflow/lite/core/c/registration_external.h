/* Copyright 2023 The TensorFlow Authors. All Rights Reserved.
Stub header — provides the TfLiteRegistrationExternal opaque type
needed by c_api.h and common.h. */
#ifndef TENSORFLOW_LITE_CORE_C_REGISTRATION_EXTERNAL_H_
#define TENSORFLOW_LITE_CORE_C_REGISTRATION_EXTERNAL_H_

#include "tensorflow/lite/core/c/c_api_types.h"

#ifdef __cplusplus
extern "C" {
#endif

/// Opaque type for a TFLite registration external object.
/// Used as a pointer in TfLiteRegistration and related APIs.
typedef struct TfLiteRegistrationExternal TfLiteRegistrationExternal;

// --------------------------------------------------------------------------
// Lifecycle

/// Creates a new TfLiteRegistrationExternal.
TFL_CAPI_EXPORT extern TfLiteRegistrationExternal*
TfLiteRegistrationExternalCreate(TfLiteBuiltinOperator builtin_code,
                                 const char* custom_name, int version);

/// Destroys a TfLiteRegistrationExternal.
TFL_CAPI_EXPORT extern void TfLiteRegistrationExternalDelete(
    TfLiteRegistrationExternal* registration);

/// Returns the builtin op code of the provided external registration.
TFL_CAPI_EXPORT extern TfLiteBuiltinOperator
TfLiteRegistrationExternalGetBuiltInCode(
    const TfLiteRegistrationExternal* registration);

/// Returns the custom name of the provided external registration (may be null).
TFL_CAPI_EXPORT extern const char* TfLiteRegistrationExternalGetCustomName(
    const TfLiteRegistrationExternal* registration);

/// Returns the version of the provided external registration.
TFL_CAPI_EXPORT extern int TfLiteRegistrationExternalGetVersion(
    const TfLiteRegistrationExternal* registration);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // TENSORFLOW_LITE_CORE_C_REGISTRATION_EXTERNAL_H_
