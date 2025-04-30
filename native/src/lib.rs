#![allow(clippy::not_unsafe_ptr_arg_deref)]

//! JNI implementation of `org.jetbrains.plugins.textmate.regex.oniguruma.OnigurumaRegexFactory`
//! and `org.jetbrains.plugins.textmate.regex.oniguruma.OnigurumaRegexFacade` classes
//!
//! There is convention for naming JNI method in native code:
//!
//! 1. prefix is `Java_`
//! 2. all dots in FQN java class name replaced with underscore
//! 3. method name is separated from class name with underscore
//!
//! So, for exampe `java.lang.System::gc()` becomes `Java_java_lang_System_gc`.

use jni::{
    objects::{JByteArray, JClass, JPrimitiveArray, ReleaseMode},
    sys::{jboolean, jbyteArray, jint, jintArray, jlong},
    JNIEnv,
};
use onig::Regex;
use onig::{RegexOptions, Region, SearchOptions, Syntax};
use onig_sys::{ONIG_OPTION_NOT_BEGIN_POSITION, ONIG_OPTION_NOT_BEGIN_STRING};
use std::{
    any::Any,
    panic::{catch_unwind, RefUnwindSafe},
    ptr, slice,
    str::{self, Utf8Error},
};

type Result<T> = std::result::Result<T, Error>;

#[derive(thiserror::Error, Debug)]
enum Error {
    #[error("JNI Error: {0}")]
    Jni(#[from] jni::errors::Error),

    #[error("Oniguruma Error: {0}")]
    Oniguruma(#[from] onig::Error),

    #[error("Unable to read UTF8 string: {0}")]
    Utf8(#[from] Utf8Error),

    #[error("String or pattern is null")]
    NullPatternOrString,

    #[error("Panic happened: {0}")]
    Panic(String),

    #[error("Null Pointer")]
    NullPointer,
}

#[no_mangle]
pub extern "C" fn Java_me_zolotov_oniguruma_Oniguruma_createRegex(
    env: JNIEnv,
    _: JClass,
    pattern: jbyteArray,
) -> jlong {
    try_catch(|| create_regex(&env, pattern))
        .propagate_exception(env)
        .unwrap_or_default()
}

#[no_mangle]
pub extern "C" fn Java_me_zolotov_oniguruma_Oniguruma_match(
    env: JNIEnv,
    _: JClass,
    regex_ptr: jlong,
    string_ptr: jlong,
    byte_offset: jint,
    match_begin_position: jboolean,
    match_begin_string: jboolean,
) -> jintArray {
    try_catch(|| {
        match_pattern(
            &env,
            regex_ptr,
            string_ptr,
            byte_offset,
            match_begin_position,
            match_begin_string,
        )
    })
    .propagate_exception(env)
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn Java_me_zolotov_oniguruma_Oniguruma_createString(
    mut env: JNIEnv,
    _: JClass,
    utf8: jbyteArray,
) -> jlong {
    // This is the only place where we can not use try_catch(), because &mut JNIEnv can not cross FFI boundary
    create_string(&mut env, utf8)
        .propagate_exception(env)
        .unwrap_or_default()
}

#[no_mangle]
pub extern "C" fn Java_me_zolotov_oniguruma_Oniguruma_freeString(
    env: JNIEnv,
    _: JClass,
    ptr: jlong,
) {
    // Be carefult to restore owned type from pointer
    try_catch(|| free::<String>(ptr)).propagate_exception(env);
}

#[no_mangle]
pub extern "C" fn Java_me_zolotov_oniguruma_Oniguruma_freeRegex(
    env: JNIEnv,
    _: JClass,
    ptr: jlong,
) {
    // Be carefult to restore owned type from pointer
    try_catch(|| free::<Regex>(ptr)).propagate_exception(env);
}

fn free<T: 'static>(ptr: i64) -> Result<()> {
    if ptr != 0 {
        unsafe {
            drop(Box::<T>::from_raw(ptr as *mut _));
        }
        Ok(())
    } else {
        Err(Error::NullPointer)
    }
}

fn create_regex(env: &JNIEnv, pattern: jbyteArray) -> Result<jlong> {
    if pattern.is_null() {
        return Ok(0);
    }
    let p = unsafe { JByteArray::from_raw(pattern) };
    let byte_array: Vec<u8> = env.convert_byte_array(p)?;
    let pattern_str = str::from_utf8(&byte_array)?;
    let regex = Regex::with_options(
        pattern_str,
        RegexOptions::REGEX_OPTION_CAPTURE_GROUP,
        Syntax::default(),
    )?;
    Ok(Box::into_raw(Box::<Regex>::new(regex)) as jlong)
}

fn match_pattern(
    env: &JNIEnv,
    regex_ptr: jlong,
    string_ptr: jlong,
    byte_offset: jint,
    match_begin_position: jboolean,
    match_begin_string: jboolean,
) -> Result<jintArray> {
    // Creating a null pointer is UB event if it is not used
    if regex_ptr == 0 || string_ptr == 0 {
        return Err(Error::NullPatternOrString);
    }
    let regex = unsafe { &*(regex_ptr as *const Regex) };
    let str = unsafe { &*(string_ptr as *mut String) };

    let mut options = SearchOptions::SEARCH_OPTION_NONE;
    if match_begin_position == 0 {
        options |= unsafe { SearchOptions::from_bits_unchecked(ONIG_OPTION_NOT_BEGIN_POSITION) };
    }
    if match_begin_string == 0 {
        options |= unsafe { SearchOptions::from_bits_unchecked(ONIG_OPTION_NOT_BEGIN_STRING) };
    }

    let mut region = Region::new();
    let matched = regex.search_with_options(
        str,
        byte_offset as usize,
        str.len(),
        options,
        Some(&mut region),
    );
    if matched.is_some() {
        let mut iterator = region.iter();

        // Constructing a Vec containing all the start and end offsets one after the other.
        //
        // Region iterator can return None, but we still need to iterate region.len() times
        // not matter what. This is not ideiomatic API, but oh well.
        let offsets = (0..region.len())
            .map(|_| iterator.next())
            .map(|i| i.map(|(s, e)| (s as i32, e as i32)))
            .map(|i| i.unwrap_or((-1, -1)))
            .flat_map(|(s, e)| [s, e])
            .collect::<Vec<_>>();
        Ok(create_jni_int_array(env, &offsets)?.into_raw())
    } else {
        Ok(ptr::null_mut())
    }
}

fn create_string(env: &mut JNIEnv, utf8: jbyteArray) -> Result<jlong> {
    if utf8.is_null() {
        Result::<jlong>::Ok(0)
    } else {
        unsafe {
            let p = JByteArray::from_raw(utf8);
            let elements = env.get_array_elements(&p, ReleaseMode::NoCopyBack)?;
            let length = env.get_array_length(&p)?;
            let slice = slice::from_raw_parts(elements.as_ptr() as *const u8, length as usize);
            let str = str::from_utf8(slice)?.to_string();
            // Need to make sure we're leaking owned type
            Ok(Box::into_raw(Box::<String>::new(str)) as jlong)
        }
    }
}

fn create_jni_int_array<'a>(env: &JNIEnv<'a>, input: &[i32]) -> Result<JPrimitiveArray<'a, i32>> {
    let array = env.new_int_array(input.len() as i32)?;
    env.set_int_array_region(&array, 0, input)?;
    Ok(array)
}

trait ToJavaException<T> {
    fn propagate_exception(self, env: JNIEnv) -> Option<T>;
}

impl<T> ToJavaException<T> for Result<T> {
    fn propagate_exception(self, mut env: JNIEnv) -> Option<T> {
        match self {
            Ok(r) => Some(r),
            Err(jni_error) => {
                if !env.exception_check().unwrap() {
                    // Only throw exception if there are no pending exception yet
                    let class = env.find_class("java/lang/RuntimeException").unwrap();
                    env.throw_new(class, jni_error.to_string()).unwrap();
                }
                None
            }
        }
    }
}

// Wraps all panics into Result with a string description of a problem
fn try_catch<T>(f: impl Fn() -> Result<T> + RefUnwindSafe) -> Result<T> {
    catch_unwind(&f).map_err(downcast_error)?
}

fn downcast_error(e: Box<dyn Any + Send>) -> Error {
    if let Some(description) = e.downcast_ref::<String>() {
        Error::Panic(description.to_string())
    } else {
        Error::Panic("Unknown".to_string())
    }
}
