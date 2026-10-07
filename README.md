# SportAndNikotin — body tracking MVP

Первый экспериментальный экран для проверки MediaPipe Pose Landmarker на реальном телефоне.

## Что уже работает

- live preview с CameraX;
- MediaPipe Pose Landmarker в режиме `LIVE_STREAM`;
- 33 landmarks тела;
- скелет поверх изображения камеры;
- front/back camera switch;
- запись каждого результата трекера в CSV;
- сохранение normalized landmarks и world landmarks;
- сохранение visibility/presence confidence;
- отдельные строки `NO_POSE`, когда MediaPipe не увидел человека;
- время inference и примерная частота результатов.

Все вычисления Pose Landmarker выполняются на устройстве.

## Запуск

1. Открой проект в Android Studio.
2. Сделай Gradle Sync.
3. При первой сборке Gradle автоматически скачает официальный MediaPipe model:
   `pose_landmarker_lite.task`.
4. Запусти приложение на физическом Android-телефоне.
5. Разреши доступ к камере.

Модель не хранится в git: она скачивается с официального MediaPipe model bucket во время сборки.

## Запись данных

Нажми **Начать запись**. Трекинг продолжит работать, а результаты начнут сохраняться в CSV.

Нажми **Остановить запись** для корректного закрытия файла.

На Android 10+ файл находится здесь:

```
Downloads/SportAndNikotin/pose_YYYYMMDD_HHmmss.csv
```

Его можно забрать обычным файловым менеджером или через ADB:

```bash
adb shell ls /sdcard/Download/SportAndNikotin
adb pull /sdcard/Download/SportAndNikotin
```

На Android 9 и ниже файл сохраняется в app-specific Documents directory; полный путь приложение показывает на экране.

## Формат CSV

Одна строка = одна landmark в одном обработанном кадре.

Основные поля:

- `frame` — номер результата MediaPipe;
- `timestamp_ms` — monotonic timestamp кадра;
- `wall_time_epoch_ms` — обычное системное время;
- `inference_ms` — задержка обработки;
- `pose_detected` — обнаружена ли поза;
- `landmark_id` / `landmark_name` — номер и имя точки;
- `x_norm/y_norm/z_norm` — координаты относительно изображения;
- `visibility/presence` — confidence;
- `x_world/y_world/z_world` — MediaPipe world coordinates;
- `world_visibility/world_presence` — confidence world landmark.

Если человек на конкретном результате не найден, пишется одна строка:

```
landmark_id=-1, landmark_name=NO_POSE
```

Это позволит отдельно оценить процент выпадений трекинга.

## Что проверять в первом эксперименте

1. Насколько скелет визуально совпадает с суставами.
2. Теряются ли точки при приседаниях, отжиманиях и поворотах.
3. Насколько шумят координаты, когда человек стоит неподвижно.
4. Что происходит при частичном выходе тела из кадра.
5. Насколько стабильны world coordinates.
6. Как меняются inference time и FPS на конкретном телефоне.

Следующий логичный шаг после сбора первых CSV — построить графики траекторий суставов, оценить шум и подобрать фильтрацию/сглаживание.
