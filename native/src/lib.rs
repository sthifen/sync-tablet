//! Lo que la app de la tablet hace sin el PC. Java llama por JNI (`Native.java`).
//!
//! Ninguna función deja salir un pánico: cruzar JNI con uno cerraría la app, y
//! un PDF raro no debe hacerlo. Si algo falla, Java usa su camino de siempre.

pub mod pdf;

use std::panic::{AssertUnwindSafe, catch_unwind};

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JIntArray};
use jni::sys::{jbyteArray, jfloat, jint, jlong, jstring};

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

fn doc<'a>(handle: jlong) -> Option<&'a mut pdf::Doc> {
    // SAFETY: el asa es de pdfOpen, sigue abierta y la usa un solo hilo a la vez.
    unsafe { (handle as *mut pdf::Doc).as_mut() }
}
