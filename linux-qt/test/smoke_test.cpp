// Drives the Qt host end to end without a display (QT_QPA_PLATFORM=offscreen): a pen stroke from
// tablet events and one from the mouse become items, ink reaches the screen, the note saves and
// reopens, undo works, and every surface is released.
#include "CanvasWidget.h"

#include <QPointingDevice>
#include <QTabletEvent>
#include <QTemporaryDir>
#include <QtTest>

class SmokeTest : public QObject {
    Q_OBJECT

    static QPointingDevice* pen() {
        static auto* device = new QPointingDevice(QStringLiteral("test pen"), 42, QInputDevice::DeviceType::Stylus,
                                                  QPointingDevice::PointerType::Pen,
                                                  QInputDevice::Capability::Position | QInputDevice::Capability::Pressure, 1, 3);
        return device;
    }

    static void tablet(QWidget* w, QEvent::Type type, QPointF at, double pressure, Qt::MouseButton button, Qt::MouseButtons buttons) {
        QTabletEvent e(type, pen(), at, w->mapToGlobal(at), pressure, 0, 0, 0, 0, 0, Qt::NoModifier, button, buttons);
        QApplication::sendEvent(w, &e);
    }

    /* The widget's pixels once the view settled (the sharp re-render follows ~90 ms after a zoom). */
    static QImage grab(CanvasWidget& c) {
        c.grab();
        QTest::qWait(200);
        return c.grab().toImage().convertToFormat(QImage::Format_RGB32);
    }

    static int inkPixels(const QImage& img, QRect area) {
        int n = 0;
        for (int y = area.top(); y <= area.bottom(); y++)
            for (int x = area.left(); x <= area.right(); x++) {
                const QColor c = img.pixelColor(x, y);
                if (c.red() > 200 && c.green() < 60 && c.blue() < 60) n++;
            }
        return n;
    }

private slots:
    void penMouseSaveReopen() {
        QTemporaryDir dir;
        QVERIFY(dir.isValid());
        xn_note* note = xn_note_new(2);
        CanvasWidget canvas;
        canvas.resize(800, 1000);
        canvas.show();
        canvas.setNote(note);
        canvas.setDark(false);
        canvas.setTool("pen");
        xn_editor_set_ink_color(canvas.editor(), 0xff0000ff);
        QCoreApplication::processEvents();
        // Zoomed in so the ink is several pixels wide: a hole where its pieces overlap would show.
        xn_editor_zoom_at(canvas.editor(), 400, 300, 4.0);
        QCoreApplication::processEvents();

        // A horizontal pen stroke with rising pressure.
        tablet(&canvas, QEvent::TabletPress, {200, 300}, 0.2, Qt::LeftButton, Qt::LeftButton);
        for (int i = 1; i <= 40; i++)
            tablet(&canvas, QEvent::TabletMove, {200.0 + i * 10, 300}, 0.2 + i * 0.02, Qt::NoButton, Qt::LeftButton);
        tablet(&canvas, QEvent::TabletRelease, {600, 300}, 0, Qt::LeftButton, Qt::NoButton);
        QCOMPARE(xn_note_item_count(note, 0), 1);
        QVERIFY(xn_note_is_dirty(note));
        QVERIFY(xn_editor_can_undo(canvas.editor()));

        QImage shot = grab(canvas);
        if (qEnvironmentVariableIsSet("XNOTES_SMOKE_SHOT")) shot.save(qEnvironmentVariable("XNOTES_SMOKE_SHOT"));
        QCOMPARE(inkPixels(shot, QRect(210, 300, 380, 1)), 380);  // solid along its middle
        QVERIFY2(inkPixels(shot, QRect(210, 290, 380, 20)) > 380 * 4, "the stroke is several pixels wide");
        QCOMPARE(inkPixels(shot, QRect(210, 500, 380, 20)), 0);
        QVERIFY(canvas.lastFrameMs() >= 0);

        // A diagonal mouse stroke.
        QTest::mousePress(&canvas, Qt::LeftButton, {}, QPoint(200, 400));
        for (int i = 1; i <= 20; i++) QTest::mouseMove(&canvas, QPoint(200 + i * 5, 400 + i * 5));
        QTest::mouseRelease(&canvas, Qt::LeftButton, {}, QPoint(300, 500));
        QCOMPARE(xn_note_item_count(note, 0), 2);
        QVERIFY(inkPixels(grab(canvas), QRect(240, 440, 20, 20)) > 20);

        xn_editor_undo(canvas.editor());
        QCOMPARE(xn_note_item_count(note, 0), 1);
        xn_editor_redo(canvas.editor());
        QCOMPARE(xn_note_item_count(note, 0), 2);

        // Other views keep painting.
        xn_editor_fit_width(canvas.editor());
        QVERIFY(inkPixels(grab(canvas), canvas.rect()) > 0);

        const QByteArray path = QFile::encodeName(dir.filePath(QStringLiteral("smoke.xnote")));
        char* error = nullptr;
        QVERIFY(xn_note_save(note, path.constData(), canvas.host(), &error));
        QVERIFY(!xn_note_is_dirty(note));

        canvas.setNote(nullptr);
        QCOMPARE(canvas.liveSurfaces(), 0);
        xn_note_close(note);

        QTemporaryDir work;
        xn_note* reopened = xn_note_open(path.constData(), QFile::encodeName(work.path()).constData(), canvas.host(), &error);
        QVERIFY2(reopened, error);
        QCOMPARE(xn_note_page_count(reopened), 2);
        QCOMPARE(xn_note_item_count(reopened, 0), 2);
        canvas.setNote(reopened);
        QVERIFY(inkPixels(grab(canvas), canvas.rect()) > 100);
        canvas.setNote(nullptr);
        QCOMPARE(canvas.liveSurfaces(), 0);
        xn_note_close(reopened);
    }

    /* The highlighter multiplies over what is under it: yellow on paper, the red ink stays red. */
    void highlighterMultiplies() {
        xn_note* note = xn_note_new(1);
        CanvasWidget canvas;
        canvas.resize(800, 1000);
        canvas.show();
        canvas.setNote(note);
        canvas.setDark(false);
        xn_editor_zoom_at(canvas.editor(), 400, 300, 4.0);
        canvas.setTool("pen");
        xn_editor_set_ink_color(canvas.editor(), 0xff0000ff);
        QTest::mousePress(&canvas, Qt::LeftButton, {}, QPoint(400, 150));
        for (int i = 1; i <= 30; i++) QTest::mouseMove(&canvas, QPoint(400, 150 + i * 10));
        QTest::mouseRelease(&canvas, Qt::LeftButton, {}, QPoint(400, 450));
        canvas.setTool("highlighter");
        xn_editor_set_ink_color(canvas.editor(), 0xffeb3bff);
        QTest::mousePress(&canvas, Qt::LeftButton, {}, QPoint(250, 300));
        for (int i = 1; i <= 30; i++) QTest::mouseMove(&canvas, QPoint(250 + i * 10, 300));
        QTest::mouseRelease(&canvas, Qt::LeftButton, {}, QPoint(550, 300));
        QCOMPARE(xn_note_item_count(note, 0), 2);

        const QImage shot = grab(canvas);
        const QColor onPaper = shot.pixelColor(300, 300), onInk = shot.pixelColor(400, 300), paper = shot.pixelColor(300, 600);
        QVERIFY2(onPaper.red() > 200 && onPaper.green() > 200 && onPaper.blue() < 200, qPrintable(onPaper.name()));
        QVERIFY2(onInk.red() > 200 && onInk.green() < 60 && onInk.blue() < 60, qPrintable(onInk.name()));
        QCOMPARE(paper, QColor(Qt::white));

        canvas.setNote(nullptr);
        QCOMPARE(canvas.liveSurfaces(), 0);
        xn_note_close(note);
    }

    void eraserEndErases() {
        xn_note* note = xn_note_new(1);
        CanvasWidget canvas;
        canvas.resize(800, 1000);
        canvas.show();
        canvas.setNote(note);
        canvas.setTool("pen");
        tablet(&canvas, QEvent::TabletPress, {200, 300}, 0.5, Qt::LeftButton, Qt::LeftButton);
        for (int i = 1; i <= 20; i++) tablet(&canvas, QEvent::TabletMove, {200.0 + i * 10, 300}, 0.5, Qt::NoButton, Qt::LeftButton);
        tablet(&canvas, QEvent::TabletRelease, {400, 300}, 0, Qt::LeftButton, Qt::NoButton);
        QCOMPARE(xn_note_item_count(note, 0), 1);

        // The pen's back end erases whatever tool is armed.
        static auto* eraser = new QPointingDevice(QStringLiteral("test eraser"), 43, QInputDevice::DeviceType::Stylus,
                                                  QPointingDevice::PointerType::Eraser,
                                                  QInputDevice::Capability::Position | QInputDevice::Capability::Pressure, 1, 3);
        auto send = [&](QEvent::Type type, QPointF at, Qt::MouseButton b, Qt::MouseButtons bs) {
            QTabletEvent e(type, eraser, at, canvas.mapToGlobal(at), 0.5, 0, 0, 0, 0, 0, Qt::NoModifier, b, bs);
            QApplication::sendEvent(&canvas, &e);
        };
        send(QEvent::TabletPress, {300, 250}, Qt::LeftButton, Qt::LeftButton);
        for (int i = 1; i <= 20; i++) send(QEvent::TabletMove, {300, 250.0 + i * 5}, Qt::NoButton, Qt::LeftButton);
        send(QEvent::TabletRelease, {300, 350}, Qt::LeftButton, Qt::NoButton);
        QCOMPARE(xn_note_item_count(note, 0), 0);

        canvas.setNote(nullptr);
        xn_note_close(note);
    }
};

QTEST_MAIN(SmokeTest)
#include "smoke_test.moc"
