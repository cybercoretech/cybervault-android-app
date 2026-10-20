//! Open a vault file with the desktop CyberVault code and list its labels.
//! Used by tools/emulator_smoke.py to prove the phone writes the same format.
//! Usage: CYBERVAULT_PASSWORD=… cargo run --example inspect -- <file>

fn main() {
    let path = std::env::args()
        .nth(1)
        .expect("usage: inspect <vault file>");
    let password = std::env::var("CYBERVAULT_PASSWORD").expect("set CYBERVAULT_PASSWORD");
    match cybervault::vault::load(std::path::Path::new(&path), &password) {
        Ok(data) => {
            for (label, entry) in &data.entries {
                println!(
                    "{label}\t{}\tsecret_len={}",
                    entry.created,
                    entry.secret.chars().count()
                );
            }
        }
        Err(e) => {
            eprintln!("desktop CyberVault could not open it: {e}");
            std::process::exit(1);
        }
    }
}
