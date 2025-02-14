use onig::Regex;
/// JNI implementation of `org.jetbrains.plugins.textmate.regex.oniguruma.OnigurumaRegexFactory`
/// and `org.jetbrains.plugins.textmate.regex.oniguruma.OnigurumaRegexFacade` classes
///
/// There is convention for naming JNI method in native code:
///
/// 1. prefix is `Java_`
/// 2. all dots in FQN java class name replaced with underscore
/// 3. method name is separated from class name with underscore
///
/// So, for exampe `java.lang.System::gc()` becomes `Java_java_lang_System_gc`.
#[cfg(feature = "jni")]
mod oniguruma {
    use core::{slice, str};
    use std::{any::Any, panic::catch_unwind};

    use super::*;
    use jni::{
        objects::{JByteArray, JClass, ReleaseMode},
        sys::{jboolean, jbyteArray, jint, jintArray, jlong},
        JNIEnv,
    };
    use onig::{RegexOptions, Region, SearchOptions, Syntax};

    #[no_mangle]
    pub extern "C" fn Java_me_zolotov_oniguruma_Oniguruma_createRegex(
        env: JNIEnv,
        _class: JClass,
        pattern: jbyteArray,
    ) -> jlong {
        create_regex(env, pattern)
    }

    #[no_mangle]
    pub extern "system" fn Java_me_zolotov_oniguruma_Oniguruma_freeRegex(_env: JNIEnv, _class: JClass, regex_ptr: jlong) {
        let regex_ptr = regex_ptr as *mut Regex;
        unsafe {
            let _ = Box::from_raw(regex_ptr);
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_me_zolotov_oniguruma_Oniguruma_match(
        env: JNIEnv,
        _class: JClass,
        regex_ptr: jlong,
        text_ptr: jlong,
        byte_offset: jint,
        match_begin_position: jboolean,
        match_begin_string: jboolean,
    ) -> jintArray {
        match_string(
            env,
            regex_ptr,
            text_ptr,
            byte_offset,
            match_begin_position,
            match_begin_string,
        )
    }

    fn create_regex(env: JNIEnv, pattern: jbyteArray) -> jlong {
        if pattern.is_null() {
            return 0;
        }
        match catch_unwind(|| {
            let p = unsafe { JByteArray::from_raw(pattern) };
            let byte_array: Vec<u8> = env
                .convert_byte_array(p)
                .expect("Failed to convert jbyteArray to Vec<u8>");
            let pattern_str = std::str::from_utf8(&byte_array).unwrap();
            let regex = Regex::with_options(
                pattern_str,
                RegexOptions::REGEX_OPTION_CAPTURE_GROUP,
                Syntax::default(),
            )
            .unwrap();
            Box::into_raw(Box::new(regex)) as jlong
        }) {
            Ok(n) => n,
            Err(e) => {
                // todo: throw exception
                println!("Error occurred {}", downcast_error(e));
                0
            }
        }
    }

    #[no_mangle]
    pub extern "C" fn Java_me_zolotov_oniguruma_Oniguruma_createString(mut env: JNIEnv, _class: JClass, utf8_content: jbyteArray) -> jlong {
        if utf8_content.is_null() {
           return 0;
        }
        let p = unsafe { JByteArray::from_raw(utf8_content) };
        let auto_elements = unsafe {
            env.get_array_elements(&p, ReleaseMode::NoCopyBack)
                .expect("Failed to convert jbyteArray to Vec<u8>")
        };
        let ptr = auto_elements.as_ptr();
        let length = env.get_array_length(&p).unwrap();
        let slice = unsafe { slice::from_raw_parts(ptr as *const u8, length as usize) };
        let str: String = std::str::from_utf8(&slice).unwrap().to_string();
        Box::into_raw(Box::new(str)) as jlong
    }

    #[no_mangle]
    pub extern "system" fn Java_me_zolotov_oniguruma_Oniguruma_freeString(_env: JNIEnv, _class: JClass, string_ptr: jlong) {
        let string_ptr = string_ptr as *mut &str;
        unsafe {
            let _ = Box::from_raw(string_ptr);
        }
    }

    fn downcast_error(e: Box<dyn Any + Send>) -> String {
        if let Some(description) = e.downcast_ref::<String>() {
            description.clone()
        } else {
            "Unknown error".to_string()
        }
    }

    #[no_mangle]
    pub extern "C" fn match_string(
        env: JNIEnv,
        regex_ptr: jlong,
        string_ptr: jlong,
        start_offset: jint,
        match_begin_position: jboolean,
        match_begin_string: jboolean,
    ) -> jintArray {
        let regex_ptr = regex_ptr as *mut Regex;
        let string_ptr = string_ptr as *mut String;

        if regex_ptr.is_null() || string_ptr.is_null() {
            return std::ptr::null_mut();
        }

        let regex = unsafe { &*regex_ptr };
        let str: &String = unsafe { &*string_ptr };

        let mut options = SearchOptions::SEARCH_OPTION_NONE;
        if match_begin_position == 0 {
            options |= unsafe {
                SearchOptions::from_bits_unchecked(onig_sys::ONIG_OPTION_NOT_BEGIN_POSITION)
            };
        }
        if match_begin_string == 0 {
            options |= unsafe {
                SearchOptions::from_bits_unchecked(onig_sys::ONIG_OPTION_NOT_BEGIN_STRING)
            };
        }
        let mut region = Region::new();
        match regex.search_with_options(
            str,
            start_offset as usize,
            str.len(),
            options,
            Some(&mut region),
        ) {
            Some(_) => {
                let mut offsets: Vec<i32> = vec![];
                let mut iterator = region.iter();
                (0..region.len()).for_each(|_i| match iterator.next() {
                    Some((start, end)) => {
                        offsets.push(start as i32);
                        offsets.push(end as i32);
                    }
                    None => {
                        offsets.push(-1);
                        offsets.push(-1);
                    }
                });

                let offsets_array = env
                    .new_int_array(offsets.len() as i32)
                    .map_err(|e| e.to_string()).unwrap();
                env.set_int_array_region(&offsets_array, 0, &offsets)
                    .map_err(|e| e.to_string()).unwrap();
                offsets_array.into_raw()
            }
            None => std::ptr::null_mut(),
        }
    }
}
