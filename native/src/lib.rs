//! Lo que la app de la tablet hace sin el PC. Java llama por JNI (`Native.java`).
//!
//! Ninguna función deja salir un pánico: cruzar JNI con uno cerraría la app, y
//! un PDF raro no debe hacerlo. Si algo falla, Java usa su camino de siempre.

pub mod deck;
pub mod grid;
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

// ---------------------------------------------------------------- hojas de cálculo (GridCraft)

fn book<'a>(handle: jlong) -> Option<&'a mut grid::Book> {
    // SAFETY: el asa viene de Box::into_raw en gridNew/gridOpen; Java la usa desde un solo hilo.
    if handle == 0 { None } else { Some(unsafe { &mut *(handle as *mut grid::Book) }) }
}

fn jstr(env: &mut JNIEnv, s: &JString) -> Option<String> {
    Some(env.get_string(s).ok()?.into())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_gridNew<'l>(_: JNIEnv<'l>, _: JClass<'l>) -> jlong {
    guarded(0, || Some(Box::into_raw(Box::new(grid::Book::new())) as jlong))
}

/// Abre un .xlsx, .xls, .ods o .csv (según `name`); 0 si no se pudo.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_gridOpen<'l>(mut env: JNIEnv<'l>, _: JClass<'l>, name: JString<'l>, data: JByteArray<'l>) -> jlong {
    guarded(0, || {
        let name = jstr(&mut env, &name)?;
        let bytes = env.convert_byte_array(&data).ok()?;
        Some(Box::into_raw(Box::new(grid::Book::open(&name, &bytes)?)) as jlong)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_gridExec<'l>(mut env: JNIEnv<'l>, _: JClass<'l>, handle: jlong, id: JString<'l>, params: JString<'l>) -> jstring {
    guarded(std::ptr::null_mut(), || {
        let id = jstr(&mut env, &id)?;
        let params = jstr(&mut env, &params)?;
        let out = book(handle)?.exec(&id, &params);
        Some(env.new_string(out).ok()?.into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_gridView<'l>(env: JNIEnv<'l>, _: JClass<'l>, handle: jlong, row: jint, col: jint, rows: jint, cols: jint) -> jstring {
    guarded(std::ptr::null_mut(), || {
        let out = book(handle)?.view(u32::try_from(row).ok()?, u32::try_from(col).ok()?, u32::try_from(rows).ok()?, u32::try_from(cols).ok()?)?;
        Some(env.new_string(out).ok()?.into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_gridEdit<'l>(env: JNIEnv<'l>, _: JClass<'l>, handle: jlong, row: jint, col: jint) -> jstring {
    guarded(std::ptr::null_mut(), || {
        let out = book(handle)?.edit_text(u32::try_from(row).ok()?, u32::try_from(col).ok()?)?;
        Some(env.new_string(out).ok()?.into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_gridCharts<'l>(env: JNIEnv<'l>, _: JClass<'l>, handle: jlong) -> jstring {
    guarded(std::ptr::null_mut(), || {
        let out = book(handle)?.charts()?;
        Some(env.new_string(out).ok()?.into_raw())
    })
}

/// El libro como `kind` (xlsx, csv, ods); null si no se pudo.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_gridSave<'l>(mut env: JNIEnv<'l>, _: JClass<'l>, handle: jlong, kind: JString<'l>) -> jbyteArray {
    guarded(std::ptr::null_mut(), || {
        let kind = jstr(&mut env, &kind)?;
        let bytes = book(handle)?.save(&kind)?;
        Some(env.byte_array_from_slice(&bytes).ok()?.into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_gridClose<'l>(_: JNIEnv<'l>, _: JClass<'l>, handle: jlong) {
    if handle != 0 {
        // SAFETY: viene de Box::into_raw y Java lo cierra una sola vez.
        drop(unsafe { Box::from_raw(handle as *mut grid::Book) });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_gridFunctions<'l>(env: JNIEnv<'l>, _: JClass<'l>) -> jstring {
    guarded(std::ptr::null_mut(), || Some(env.new_string(grid::functions()).ok()?.into_raw()))
}

/// Calcula una tabla de una página con GridCraft: celdas por filas → lo que se ve.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_gridEvalTable<'l>(mut env: JNIEnv<'l>, _: JClass<'l>, cells: JObjectArray<'l>, rows: jint, cols: jint) -> jni::sys::jobjectArray {
    guarded(std::ptr::null_mut(), || {
        let n = env.get_array_length(&cells).ok()?;
        let mut raw = Vec::with_capacity(usize::try_from(n).ok()?);
        for i in 0..n {
            let o = env.get_object_array_element(&cells, i).ok()?;
            if o.is_null() {
                raw.push(String::new());
            } else {
                let s = JString::from(o);
                raw.push(jstr(&mut env, &s)?);
            }
        }
        let out = grid::eval_table(&raw, u32::try_from(rows).ok()?, u32::try_from(cols).ok()?);
        let arr = env.new_object_array(i32::try_from(out.len()).ok()?, "java/lang/String", jni::objects::JObject::null()).ok()?;
        for (i, v) in out.iter().enumerate() {
            let s = env.new_string(v).ok()?;
            env.set_object_array_element(&arr, i32::try_from(i).ok()?, s).ok()?;
        }
        Some(arr.into_raw())
    })
}

/// HTML del editor de documentos como `.docx`; null si no se pudo.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_htmlToDocx<'l>(mut env: JNIEnv<'l>, _: JClass<'l>, html: JString<'l>) -> jbyteArray {
    guarded(std::ptr::null_mut(), || {
        let html: String = env.get_string(&html).ok()?.into();
        let docx = word::html_to_docx(&html)?;
        Some(env.byte_array_from_slice(&docx).ok()?.into_raw())
    })
}

/// Un `.docx` como HTML; null si no se pudo leer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_synctablet_Native_docxToHtml<'l>(env: JNIEnv<'l>, _: JClass<'l>, data: JByteArray<'l>) -> jstring {
    guarded(std::ptr::null_mut(), || {
        let bytes = env.convert_byte_array(&data).ok()?;
        let html = word::docx_to_html(&bytes)?;
        Some(env.new_string(html).ok()?.into_raw())
    })
}
