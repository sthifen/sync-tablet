//! Lo que la app de la tablet hace sin el PC. Java llama por JNI (`Native.java`).
//!
//! Ninguna función deja salir un pánico: cruzar JNI con uno cerraría la app, y
//! un PDF raro no debe hacerlo. Si algo falla, Java usa su camino de siempre.

pub mod deck;
pub mod image;
pub mod pdf;
pub mod word;

use std::panic::{AssertUnwindSafe, catch_unwind};

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JIntArray, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jfloat, jint, jlong, jstring};

fn guarded<T>(fallback: T, f: impl FnOnce() -> Option<T>) -> T {
    catch_unwind(AssertUnwindSafe(f)).ok().flatten().unwrap_or(fallback)
}

/// Abre un PDF y devuelve su asa, o 0 si no se puede leer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_pdfOpen<'l>(env: JNIEnv<'l>, _: JClass<'l>, data: JByteArray<'l>) -> jlong {
    guarded(0, || {
        let bytes = env.convert_byte_array(&data).ok()?;
        let doc = pdf::Doc::open(bytes)?;
        Some(Box::into_raw(Box::new(doc)) as jlong)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_pdfPages<'l>(_: JNIEnv<'l>, _: JClass<'l>, handle: jlong) -> jint {
    guarded(0, || Some(doc(handle)?.pages() as jint))
}

/// Dibuja una página a `width` píxeles de ancho: RGBA premultiplicado, y en
/// `size` el ancho y alto reales. Null si no se pudo.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_pdfRender<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    page: jint,
    width: jfloat,
    size: JIntArray<'l>,
) -> jbyteArray {
    guarded(std::ptr::null_mut(), || {
        let image = doc(handle)?.render(usize::try_from(page).ok()?, width)?;
        env.set_int_array_region(&size, 0, &[image.width as jint, image.height as jint]).ok()?;
        Some(env.byte_array_from_slice(&image.rgba).ok()?.into_raw())
    })
}

/// El texto de una página; vacío si no tiene (un escaneo, por ejemplo).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_pdfText<'l>(env: JNIEnv<'l>, _: JClass<'l>, handle: jlong, page: jint) -> jstring {
    guarded(std::ptr::null_mut(), || {
        let text = doc(handle)?.text(usize::try_from(page).ok()?).unwrap_or_default();
        Some(env.new_string(text).ok()?.into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_pdfClose<'l>(_: JNIEnv<'l>, _: JClass<'l>, handle: jlong) {
    if handle != 0 {
        // SAFETY: viene de Box::into_raw en pdfOpen y Java lo cierra una sola vez.
        drop(unsafe { Box::from_raw(handle as *mut pdf::Doc) });
    }
}

/// Un `.docx` como JSON de nota; null si no se pudo leer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_docxToNote<'l>(env: JNIEnv<'l>, _: JClass<'l>, data: JByteArray<'l>) -> jstring {
    guarded(std::ptr::null_mut(), || {
        let bytes = env.convert_byte_array(&data).ok()?;
        let json = word::docx_to_note(&bytes)?;
        Some(env.new_string(json).ok()?.into_raw())
    })
}

/// Una nota (JSON) como `.docx`; null si no se pudo.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_noteToDocx<'l>(mut env: JNIEnv<'l>, _: JClass<'l>, note: JString<'l>) -> jbyteArray {
    guarded(std::ptr::null_mut(), || {
        let note: String = env.get_string(&note).ok()?.into();
        let docx = word::note_to_docx(&note)?;
        Some(env.byte_array_from_slice(&docx).ok()?.into_raw())
    })
}

/// Abre un `.pptx` y devuelve su asa, o 0 si no se puede leer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_deckOpen<'l>(env: JNIEnv<'l>, _: JClass<'l>, data: JByteArray<'l>) -> jlong {
    guarded(0, || {
        let bytes = env.convert_byte_array(&data).ok()?;
        Some(Box::into_raw(Box::new(deck::Deck::open(&bytes)?)) as jlong)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_deckSlides<'l>(_: JNIEnv<'l>, _: JClass<'l>, handle: jlong) -> jint {
    guarded(0, || Some(deck(handle)?.slides() as jint))
}

/// Dibuja una diapositiva: RGBA premultiplicado, y en `size` ancho y alto.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_deckRender<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    slide: jint,
    width: jfloat,
    size: JIntArray<'l>,
) -> jbyteArray {
    guarded(std::ptr::null_mut(), || {
        let image = deck(handle)?.render(usize::try_from(slide).ok()?, f64::from(width))?;
        env.set_int_array_region(&size, 0, &[image.width as jint, image.height as jint]).ok()?;
        Some(env.byte_array_from_slice(&image.rgba).ok()?.into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_deckText<'l>(env: JNIEnv<'l>, _: JClass<'l>, handle: jlong, slide: jint) -> jstring {
    guarded(std::ptr::null_mut(), || {
        let text = deck(handle)?.text(usize::try_from(slide).ok()?);
        Some(env.new_string(text).ok()?.into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_deckClose<'l>(_: JNIEnv<'l>, _: JClass<'l>, handle: jlong) {
    if handle != 0 {
        // SAFETY: viene de Box::into_raw en deckOpen y Java lo cierra una sola vez.
        drop(unsafe { Box::from_raw(handle as *mut deck::Deck) });
    }
}

/// Un `.pptx` con una imagen JPEG por diapositiva; null si no se pudo.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_imagesToPptx<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    images: JObjectArray<'l>,
    width: jfloat,
    height: jfloat,
) -> jbyteArray {
    guarded(std::ptr::null_mut(), || {
        let count = env.get_array_length(&images).ok()?;
        let mut jpegs = Vec::with_capacity(count as usize);
        for i in 0..count {
            let item = JByteArray::from(env.get_object_array_element(&images, i).ok()?);
            jpegs.push(env.convert_byte_array(&item).ok()?);
        }
        let pptx = deck::images_to_pptx(jpegs, f64::from(width), f64::from(height))?;
        Some(env.byte_array_from_slice(&pptx).ok()?.into_raw())
    })
}

/// Una foto editada, como JPEG; null si no se pudo.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_dev_synctablet_Native_imageEdit<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    data: JByteArray<'l>,
    brightness: jfloat,
    contrast: jfloat,
    saturation: jfloat,
    quarter_turns: jint,
    left: jfloat,
    top: jfloat,
    right: jfloat,
    bottom: jfloat,
    document: jboolean,
) -> jbyteArray {
    guarded(std::ptr::null_mut(), || {
        let bytes = env.convert_byte_array(&data).ok()?;
        let edit = image::Edit {
            brightness,
            contrast,
            saturation,
            quarter_turns: quarter_turns.rem_euclid(4) as u8,
            crop: [left, top, right, bottom],
            document: document != 0,
        };
        let jpeg = image::edit(&bytes, &edit)?;
        Some(env.byte_array_from_slice(&jpeg).ok()?.into_raw())
    })
}

fn deck<'a>(handle: jlong) -> Option<&'a deck::Deck> {
    // SAFETY: el asa es de deckOpen y sigue abierta.
    unsafe { (handle as *const deck::Deck).as_ref() }
}

fn doc<'a>(handle: jlong) -> Option<&'a mut pdf::Doc> {
    // SAFETY: el asa es de pdfOpen, sigue abierta y la usa un solo hilo a la vez.
    unsafe { (handle as *mut pdf::Doc).as_mut() }
}
